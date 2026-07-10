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

import static org.assertj.core.api.Assertions.assertThat;

class StacktaleMcpServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ST_FILE = """
            # AI-oriented error reports (format st/1, https://github.com/GabrielBBaldez/stacktale)
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
        assertThat(r[1].at("/result/tools")).hasSize(3);
        assertThat(r[1].at("/result/tools/0/name").asText()).isEqualTo("list_errors");
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
}
