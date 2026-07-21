package io.github.gabrielbbaldez.stacktale.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StacktaleMcpServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ST_FILE = """
            # AI-oriented error reports (format st/1, https://github.com/stacktale/stacktale)
            # header lines...
            ━━━ ERROR #aaaa1111 ━━━ 2026-07-10 10:00:00.000 thread=main ━━━
            NullPointerException: customer is null
            at Svc.run(Svc.java:1) ← YOUR CODE

            env: app=demo | java 21 | linux
            ━━━ END #aaaa1111 ━━━
            ━ #aaaa1111 repeated 4× (last 10:00:05.000) ━
            ─── app start 2026-07-10 11:00:00.000 (pid 1) ───
            ━━━ ERROR #bbbb2222 ━━━ 2026-07-10 11:30:00.000 thread=worker ━━━
            IllegalStateException: gateway timeout
            at Pay.charge(Pay.java:9) ← YOUR CODE

            env: app=demo | java 21 | linux
            ━━━ END #bbbb2222 ━━━
            """;

    private Path file;

    @BeforeEach
    void writeFile(@TempDir Path dir) throws Exception {
        file = dir.resolve("errors-ai.log");
        Files.writeString(file, ST_FILE, StandardCharsets.UTF_8);
    }

    private JsonNode[] roundTrip(String... requests) throws Exception {
        String input = String.join("\n", requests) + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new StacktaleMcpServer(file).serve(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        String[] lines = out.toString(StandardCharsets.UTF_8).trim().split("\n");
        JsonNode[] responses = new JsonNode[lines.length];
        for (int i = 0; i < lines.length; i++) responses[i] = JSON.readTree(lines[i]);
        return responses;
    }

    @Test
    void initializeAndListTools() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        assertThat(r[0].at("/result/serverInfo/name").asText()).isEqualTo("stacktale");
        assertThat(r[0].at("/result/capabilities/resources/subscribe").asBoolean()).isTrue();
        assertThat(r[0].at("/result/capabilities/prompts").isObject()).isTrue();
        assertThat(r[1].at("/result/tools")).hasSize(6);
        assertThat(r[1].at("/result/tools/0/name").asText()).isEqualTo("list_errors");
        assertThat(r[1].at("/result/tools/3/name").asText()).isEqualTo("find_similar_errors");
        assertThat(r[1].at("/result/tools/4/name").asText()).isEqualTo("errors_since_last_check");
        assertThat(r[1].at("/result/tools/5/name").asText()).isEqualTo("match_report");
    }

    @Test
    void matchReportReturnsTheFullBlockForAPastedTrace() throws Exception {
        // a raw trace with a Caused-by whose root cause is the #aaaa1111 NPE in ST_FILE
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"match_report\","
                + "\"arguments\":{\"trace\":\"jakarta.servlet.ServletException: request failed\\n"
                + "    at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:100)\\n"
                + "Caused by: java.lang.NullPointerException: customer is null\\n"
                + "    at com.acme.Svc.run(Svc.java:1)\"}}}");
        String text = r[0].at("/result/content/0/text").asText();
        assertThat(text)
                .contains("#aaaa1111")                       // matched the root cause, not the wrapper
                .contains("━━━ ERROR #aaaa1111")             // full report block returned
                .contains("← YOUR CODE");
    }

    @Test
    void matchReportWithNoMatchSaysSo() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"match_report\","
                + "\"arguments\":{\"trace\":\"com.acme.WidgetException: the frobnicator jammed\"}}}");
        assertThat(r[0].at("/result/content/0/text").asText()).contains("No captured report matches");
    }

    private static final String NEW_BLOCK = """
            ━━━ ERROR #cccc3333 ━━━ 2026-07-10 12:00:00.000 thread=main ━━━
            IllegalArgumentException: bad input
            ━━━ END #cccc3333 ━━━
            """;

    // One fix-loop check on a persistent server instance; the cursor lives on the instance,
    // so calling serve() again (a fresh stdio "turn") keeps it while the file changes between.
    private static String loopCheck(StacktaleMcpServer server, int id, boolean reset) throws Exception {
        String args = reset ? "{\"reset\":true}" : "{}";
        String req = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\"errors_since_last_check\",\"arguments\":" + args + "}}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serve(new ByteArrayInputStream(req.getBytes(StandardCharsets.UTF_8)), out);
        return JSON.readTree(out.toString(StandardCharsets.UTF_8).trim()).at("/result/content/0/text").asText();
    }

    @Test
    void fixLoopReportsNewRecurringAndClean(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("errors-ai.log");
        Files.writeString(f, ST_FILE, StandardCharsets.UTF_8); // #aaaa1111 (×4) and #bbbb2222
        StacktaleMcpServer server = new StacktaleMcpServer(f);

        // first call baselines and shows what's already there
        assertThat(loopCheck(server, 1, false))
                .contains("currently on file").contains("aaaa1111").contains("bbbb2222");

        // nothing changed → the loop's clean signal
        assertThat(loopCheck(server, 2, false)).contains("No new errors");

        // a brand-new error appears
        Files.writeString(f, ST_FILE + NEW_BLOCK, StandardCharsets.UTF_8);
        String afterNew = loopCheck(server, 3, false);
        assertThat(afterNew).contains("new").contains("cccc3333");
        assertThat(afterNew).doesNotContain("still occurring");

        // an already-seen error recurs (a repeated line lifts #aaaa1111 above its baseline count)
        Files.writeString(f, ST_FILE + NEW_BLOCK
                + "━ #aaaa1111 repeated 9× (last 10:00:09.000) ━\n", StandardCharsets.UTF_8);
        String afterRecur = loopCheck(server, 4, false);
        assertThat(afterRecur).contains("still occurring").contains("aaaa1111");
        assertThat(afterRecur).doesNotContain("cccc3333"); // cccc3333 was already reported, not new again

        // reset re-baselines: the current file becomes the new starting point
        assertThat(loopCheck(server, 5, true)).contains("currently on file");
    }

    @Test
    void negotiatesProtocolAndReportsARealVersion() throws Exception {
        // the client's offered revision is echoed back, not overridden
        JsonNode[] echoed = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}");
        assertThat(echoed[0].at("/result/protocolVersion").asText()).isEqualTo("2025-03-26");
        assertThat(echoed[0].at("/result/serverInfo/version").asText())
                .isNotBlank().isNotEqualTo("0.4.0"); // real version, not the old hard-coded string

        // no offer → our own preferred revision
        JsonNode[] fallback = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertThat(fallback[0].at("/result/protocolVersion").asText()).isEqualTo("2025-06-18");
    }

    @Test
    void listsAndGetsPrompts() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"prompts/list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/get\",\"params\":{\"name\":\"fix_loop\"}}");
        assertThat(r[0].at("/result/prompts")).hasSize(2);
        assertThat(r[0].at("/result/prompts/0/name").asText()).isEqualTo("fix_loop");
        JsonNode message = r[1].at("/result/messages/0");
        assertThat(message.at("/role").asText()).isEqualTo("user");
        assertThat(message.at("/content/text").asText()).contains("errors_since_last_check");
    }

    @Test
    void toolsCarryStructuredContent() throws Exception {
        // list_errors → { reports: [ {id, headline, repeats, timestamp} ] }
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"list_errors\",\"arguments\":{}}}");
        JsonNode reports = r[0].at("/result/structuredContent/reports");
        assertThat(reports.isArray()).isTrue();
        assertThat(reports).isNotEmpty();
        assertThat(reports.get(0).at("/id").asText()).isNotBlank();
        assertThat(reports.get(0).at("/headline").asText()).isNotBlank();

        // errors_since_last_check → { clean, new, recurring }; ST_FILE has errors so not clean
        JsonNode[] loop = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"errors_since_last_check\",\"arguments\":{}}}");
        JsonNode sc = loop[0].at("/result/structuredContent");
        assertThat(sc.at("/clean").asBoolean()).isFalse();
        assertThat(sc.at("/new").isArray()).isTrue();
        assertThat(sc.at("/recurring").isArray()).isTrue();
    }

    @Test
    void toolsAdvertiseReadOnlyAnnotations() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        JsonNode tools = r[0].at("/result/tools");
        tools.forEach(t -> {
            assertThat(t.at("/annotations/readOnlyHint").asBoolean()).isTrue();  // all read the file only
            assertThat(t.at("/annotations/title").asText()).isNotBlank();
        });
        // the loop tool declares an outputSchema for its structured result
        JsonNode loopTool = null;
        for (JsonNode t : tools) {
            if ("errors_since_last_check".equals(t.get("name").asText())) loopTool = t;
        }
        assertThat(loopTool.at("/outputSchema/properties/clean").isObject()).isTrue();
        JsonNode loop = null;
        for (JsonNode t : tools) {
            if ("errors_since_last_check".equals(t.get("name").asText())) loop = t;
        }
        assertThat(loop).isNotNull();
        // the cursor tool mutates session state, so it must NOT claim idempotence
        assertThat(loop.at("/annotations/idempotentHint").asBoolean()).isFalse();
    }

    @Test
    void listsAndReadsTheReportsResource() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/read\",\"params\":{\"uri\":\"stacktale://reports\"}}");
        assertThat(r[0].at("/result/resources/0/uri").asText()).isEqualTo("stacktale://reports");
        assertThat(r[1].at("/result/contents/0/text").asText()).contains("#bbbb2222");
    }

    @Test
    void subscribePushesAnUpdateNotificationWhenTheFileChanges(@TempDir Path dir) throws Exception {
        Path watched = dir.resolve("errors-ai.log");
        Files.writeString(watched, ST_FILE, StandardCharsets.UTF_8);
        StacktaleMcpServer server = new StacktaleMcpServer(watched);

        java.io.PipedOutputStream toServer = new java.io.PipedOutputStream();
        java.io.PipedInputStream serverIn = new java.io.PipedInputStream(toServer, 8192);
        ByteArrayOutputStream serverOut = new ByteArrayOutputStream();

        Thread serving = new Thread(() -> {
            try { server.serve(serverIn, new java.io.FilterOutputStream(serverOut) {
                @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
                    synchronized (serverOut) { super.out.write(b, off, len); }
                }
            }); } catch (Exception ignored) {}
        });
        serving.setDaemon(true);
        serving.start();

        toServer.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/subscribe\",\"params\":{}}\n"
                .getBytes(StandardCharsets.UTF_8));
        toServer.flush();
        Thread.sleep(400); // let the watcher register

        Files.writeString(watched, ST_FILE + "extra append\n", StandardCharsets.UTF_8);

        String out = "";
        for (int i = 0; i < 60 && !out.contains("notifications/resources/updated"); i++) {
            Thread.sleep(100);
            synchronized (serverOut) { out = serverOut.toString(StandardCharsets.UTF_8); }
        }
        toServer.close();
        assertThat(out).contains("notifications/resources/updated").contains("stacktale://reports");
    }

    @Test
    void listErrorsNewestFirstWithRepeats() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"list_errors\",\"arguments\":{}}}");
        String text = r[0].at("/result/content/0/text").asText();
        assertThat(text.lines().findFirst().orElse("")).contains("#bbbb2222"); // newest first
        assertThat(text).contains("(×4)");                                     // repeat count folded in
        assertThat(text).contains("NullPointerException: customer is null");
    }

    @Test
    void recurrenceSurvivesASecondBlockOfTheSameFingerprint(@TempDir Path dir) throws Exception {
        // the same error reported twice (dedup window expired between) — the MCP view must
        // not reset its recurrence to 1 and must keep the earliest timestamp
        Path f = dir.resolve("errors-ai.log");
        Files.writeString(f, """
                ━━━ ERROR #dupe1234 ━━━ 2026-07-10 09:00:00.000 thread=main ━━━
                RuntimeException: recurring
                ━━━ END #dupe1234 ━━━
                ━ #dupe1234 repeated 3× (last 09:00:05.000) ━
                ━━━ ERROR #dupe1234 ━━━ 2026-07-10 09:30:00.000 thread=main ━━━
                RuntimeException: recurring
                ━━━ END #dupe1234 ━━━
                """, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new StacktaleMcpServer(f).serve(new ByteArrayInputStream(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"list_errors\",\"arguments\":{}}}\n"
                        .getBytes(StandardCharsets.UTF_8)), out);
        String text = JSON.readTree(out.toString(StandardCharsets.UTF_8).trim())
                .at("/result/content/0/text").asText();
        assertThat(text).contains("(×3)");                 // count carried forward, not reset to 1
        assertThat(text).contains("2026-07-10 09:00:00");  // earliest timestamp kept
    }

    @Test
    void readsTheStJsonFormatToo(@TempDir Path dir) throws Exception {
        // a format=json errors-ai.log (st-json/1 NDJSON) must work through the same tools
        Path f = dir.resolve("errors-ai.log");
        Files.writeString(f, """
                {"type":"header","format":"st-json/1"}
                {"type":"report","id":"json1234","ts":"2026-07-10T20:16:40.412Z","thread":"main","error":{"type":"IllegalStateException","message":"payment gateway refused"}}
                {"type":"repeat","id":"json1234","count":5,"last":"2026-07-10T20:17:00.000Z"}
                """, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new StacktaleMcpServer(f).serve(new ByteArrayInputStream((
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"list_errors\",\"arguments\":{}}}\n"
              + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"get_report\",\"arguments\":{\"id\":\"json1234\"}}}\n")
                .getBytes(StandardCharsets.UTF_8)), out);
        String[] lines = out.toString(StandardCharsets.UTF_8).trim().split("\n");

        String list = JSON.readTree(lines[0]).at("/result/content/0/text").asText();
        assertThat(list)
                .contains("json1234")
                .contains("IllegalStateException: payment gateway refused") // headline built from the JSON error
                .contains("(×5)");                                          // the repeat entry folded in
        String report = JSON.readTree(lines[1]).at("/result/content/0/text").asText();
        assertThat(report).contains("payment gateway refused"); // the report block served from JSON
    }

    @Test
    void getReportReturnsTheFullBlock() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_report\",\"arguments\":{\"id\":\"aaaa1111\"}}}");
        String text = r[0].at("/result/content/0/text").asText();
        assertThat(text).contains("━━━ ERROR #aaaa1111").contains("← YOUR CODE").contains("occurred 4×");
    }

    @Test
    void errorsSinceFiltersByTimestamp() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"errors_since\",\"arguments\":{\"since\":\"2026-07-10 11:00:00\"}}}");
        String text = r[0].at("/result/content/0/text").asText();
        assertThat(text).contains("#bbbb2222").doesNotContain("#aaaa1111");
    }

    @Test
    void unknownToolReturnsJsonRpcError() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\",\"arguments\":{}}}");
        assertThat(r[0].has("error")).isTrue();
    }

    @Test
    void unknownMethodUsesMethodNotFoundCode() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"does/not/exist\",\"params\":{}}");
        assertThat(r[0].at("/error/code").asInt()).isEqualTo(-32601); // Method not found, per JSON-RPC 2.0
    }

    @Test
    void scansContiguousBackupsBeyondNine(@TempDir Path dir) throws Exception {
        // a report living in .12 (maxBackups > 9) must still be visible
        Files.writeString(dir.resolve("errors-ai.log.12"), """
                ━━━ ERROR #old01234 ━━━ 2026-07-01 09:00:00.000 thread=main ━━━
                RuntimeException: ancient failure
                ━━━ END #old01234 ━━━
                """, StandardCharsets.UTF_8);
        for (int i = 1; i <= 12; i++) {
            if (i == 12) continue;
            Files.writeString(dir.resolve("errors-ai.log." + i), "filler\n", StandardCharsets.UTF_8);
        }
        Files.writeString(dir.resolve("errors-ai.log"), ST_FILE, StandardCharsets.UTF_8);

        StacktaleMcpServer server = new StacktaleMcpServer(dir.resolve("errors-ai.log"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serve(new ByteArrayInputStream(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_report\",\"arguments\":{\"id\":\"old01234\"}}}\n"
                        .getBytes(StandardCharsets.UTF_8)), out);
        JsonNode r = JSON.readTree(out.toString(StandardCharsets.UTF_8).trim());
        assertThat(r.at("/result/content/0/text").asText()).contains("ancient failure");
    }

    @Test
    void findSimilarRanksByExceptionTypeAndNormalizedMessage() {
        // #67: same root-cause type + digit-normalized message ranks first; unrelated errors
        // score 0 and drop out entirely.
        List<StReportFile.StReport> reports = List.of(
                report("aaa11111", "NullPointerException: customer is null"),
                report("bbb22222", "NullPointerException: Cannot invoke \"Customer.tier()\" because \"customer\" is null"),
                report("ccc33333", "IllegalStateException: payment gateway refused"),
                report("ddd44444", "SQLException: connection timed out"));

        List<StReportFile.StReport> hits = StacktaleMcpServer.rank(
                "NullPointerException: customer 8842 is null", reports, 5);

        assertThat(hits).extracting(r -> r.id()).containsExactly("aaa11111", "bbb22222");
    }

    private static StReportFile.StReport report(String id, String headline) {
        return new StReportFile.StReport(id, "2026-07-14 10:00:00.000", headline, 1, "block");
    }
}
