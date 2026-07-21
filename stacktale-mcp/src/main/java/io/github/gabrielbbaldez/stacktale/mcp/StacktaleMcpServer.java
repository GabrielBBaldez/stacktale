package io.github.gabrielbbaldez.stacktale.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gabrielbbaldez.stacktale.mcp.StReportFile.StReport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tiny read-only MCP server (JSON-RPC 2.0 over stdio) that lets AI assistants query
 * stacktale reports as tools instead of reading files:
 *
 * <pre>
 * { "mcpServers": { "stacktale": {
 *     "command": "java",
 *     "args": ["-jar", "stacktale-mcp.jar", "--file", "path/to/errors-ai.log"]
 * }}}
 * </pre>
 *
 * Tools: {@code list_errors}, {@code get_report}, {@code errors_since}. No network, no
 * writes — it parses the st/1 file (and its rotated backups) on demand.
 */
public final class StacktaleMcpServer {

    /** Our preferred MCP revision — used only when the client doesn't offer one to echo. */
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StReportFile reports;
    private final Path file;

    StacktaleMcpServer(Path file) {
        this.file = file;
        this.reports = new StReportFile(file);
    }

    public static void main(String[] args) throws Exception {
        // precedence: --file argument > STACKTALE_FILE env var > default
        Path file = Path.of("errors-ai.log");
        String env = System.getenv("STACKTALE_FILE");
        if (env != null && !env.isBlank()) file = Path.of(env);
        for (int i = 0; i < args.length - 1; i++) {
            if ("--file".equals(args[i])) file = Path.of(args[i + 1]);
        }
        new StacktaleMcpServer(file).serve(System.in, System.out);
    }

    /** The single resource this server exposes: the live error report file. */
    static final String RESOURCE_URI = "stacktale://reports";

    private PrintStream writer;
    private final Object writeLock = new Object();
    private volatile boolean subscribed;
    private Thread watcherThread;

    // Per-session cursor for the fix-loop tool: report id -> highest occurrence count already
    // shown to the agent. Unseen id = new error; a higher count = the same error recurred.
    private final Map<String, Integer> seenRepeats = new HashMap<>();
    private boolean baselineTaken;

    void serve(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.writer = new PrintStream(out, true, StandardCharsets.UTF_8);
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode request;
            try {
                request = JSON.readTree(line);
            } catch (IOException e) {
                continue; // not JSON — ignore, stdio must stay alive
            }
            JsonNode idNode = request.get("id");
            String method = request.path("method").asText("");
            if (idNode == null) continue; // notification (e.g. notifications/initialized)
            ObjectNode response = JSON.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", idNode);
            try {
                response.set("result", handle(method, request.path("params")));
            } catch (UnknownMethodException e) {
                ObjectNode error = response.putObject("error");
                error.put("code", -32601); // Method not found (JSON-RPC 2.0)
                error.put("message", e.getMessage());
            } catch (Exception e) {
                ObjectNode error = response.putObject("error");
                error.put("code", -32603); // Internal error
                error.put("message", String.valueOf(e.getMessage()));
            }
            send(response);
        }
        subscribed = false;
        if (watcherThread != null) watcherThread.interrupt();
    }

    /** Serializes every write to stdout so watcher notifications never interleave with responses. */
    private void send(ObjectNode message) {
        try {
            String json = JSON.writeValueAsString(message);
            synchronized (writeLock) {
                writer.println(json);
            }
        } catch (Exception ignored) {
            // a serialization failure must not kill the read loop
        }
    }

    private static final class UnknownMethodException extends RuntimeException {
        UnknownMethodException(String message) { super(message); }
    }

    private JsonNode handle(String method, JsonNode params) throws IOException {
        return switch (method) {
            case "initialize" -> initialize(params);
            case "tools/list" -> toolsList();
            case "tools/call" -> toolsCall(params);
            case "resources/list" -> resourcesList();
            case "resources/read" -> resourcesRead(params);
            case "resources/subscribe" -> resourcesSubscribe();
            case "resources/unsubscribe" -> resourcesUnsubscribe();
            case "prompts/list" -> promptsList();
            case "prompts/get" -> promptsGet(params);
            case "ping" -> JSON.createObjectNode();
            default -> throw new UnknownMethodException("unknown method: " + method);
        };
    }

    private JsonNode initialize(JsonNode params) {
        ObjectNode result = JSON.createObjectNode();
        // Echo the client's requested revision when it offered one (the spec's negotiation);
        // only dictate our own when the client sent none. Our capabilities are stable across
        // these revisions, so accepting the client's keeps every client happy.
        String requested = params.path("protocolVersion").asText("");
        result.put("protocolVersion", requested.isBlank() ? PROTOCOL_VERSION : requested);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        // prompts: clients surface these as slash-commands ("run the fix-loop") natively
        capabilities.putObject("prompts").put("listChanged", false);
        // resources with subscribe: the AI is TOLD when a new error lands, it doesn't poll
        ObjectNode res = capabilities.putObject("resources");
        res.put("subscribe", true);
        res.put("listChanged", false);
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "stacktale");
        info.put("version", serverVersion());
        return result;
    }

    /** The real artifact version from the jar manifest; {@code "dev"} when run from classes. */
    private static String serverVersion() {
        String v = StacktaleMcpServer.class.getPackage().getImplementationVersion();
        return v != null && !v.isBlank() ? v : "dev";
    }

    private JsonNode resourcesList() {
        ObjectNode result = JSON.createObjectNode();
        ObjectNode resource = result.putArray("resources").addObject();
        resource.put("uri", RESOURCE_URI);
        resource.put("name", "Error reports");
        resource.put("description", "The live stacktale error report file — subscribe to be notified of new errors.");
        resource.put("mimeType", "text/plain");
        return result;
    }

    private JsonNode resourcesRead(JsonNode params) throws IOException {
        String uri = params.path("uri").asText();
        ObjectNode result = JSON.createObjectNode();
        ObjectNode contents = result.putArray("contents").addObject();
        contents.put("uri", uri);
        contents.put("mimeType", "text/plain");
        contents.put("text", listErrors(50).text()); // the "what's there now" view; get_report for full blocks
        return result;
    }

    private JsonNode resourcesSubscribe() {
        subscribed = true;
        startWatcher();
        return JSON.createObjectNode();
    }

    private JsonNode resourcesUnsubscribe() {
        subscribed = false;
        return JSON.createObjectNode();
    }

    /** Watches the report file's directory and pushes an updated-notification when it changes. */
    private synchronized void startWatcher() {
        if (watcherThread != null && watcherThread.isAlive()) return;
        watcherThread = new Thread(this::watchLoop, "stacktale-mcp-watch");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private void watchLoop() {
        java.nio.file.Path dir = file.toAbsolutePath().getParent();
        if (dir == null) return;
        try (java.nio.file.WatchService ws = dir.getFileSystem().newWatchService()) {
            dir.register(ws, java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                    java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
            String target = file.getFileName().toString();
            while (subscribed && !Thread.currentThread().isInterrupted()) {
                java.nio.file.WatchKey key = ws.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (key == null) continue;
                boolean changed = key.pollEvents().stream()
                        .anyMatch(e -> target.equals(String.valueOf(e.context())));
                key.reset();
                if (!changed) continue;
                // debounce: one error is often several appends (the block, a flush, maybe a
                // repeat counter). Swallow the burst and push a SINGLE updated-notification so
                // the agent re-reads once — not once per write.
                java.nio.file.WatchKey burst;
                while ((burst = ws.poll(150, java.util.concurrent.TimeUnit.MILLISECONDS)) != null) {
                    burst.pollEvents();
                    burst.reset();
                }
                if (subscribed) notifyResourceUpdated();
            }
        } catch (Exception ignored) {
            // the watcher is best-effort; failing it never affects the request loop
        }
    }

    private void notifyResourceUpdated() {
        ObjectNode note = JSON.createObjectNode();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/resources/updated");
        note.putObject("params").put("uri", RESOURCE_URI);
        send(note);
    }

    // Kept in sync with the "fix-loop" section of docs/mcp-setup.md.
    private static final String FIX_LOOP_PROMPT =
            "Use the stacktale MCP server as a fix loop. Call errors_since_last_check to see the "
            + "errors currently on file. Make a fix, then re-run the app or tests. Call "
            + "errors_since_last_check again and address whatever comes back as new or still "
            + "occurring (use get_report <id> for the full block). Repeat until it reports "
            + "\"No new errors\". If I paste a raw stack trace, call match_report first to pull the "
            + "captured report for it.";

    private static final String EXPLAIN_LATEST_PROMPT =
            "Call list_errors to find the most recent stacktale error, get_report its id, and explain "
            + "the root cause using the report's story, fields and distilled stack — then propose a "
            + "concrete fix.";

    private JsonNode promptsList() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode prompts = result.putArray("prompts");
        prompt(prompts, "fix_loop", "Fix errors in a loop with stacktale until the app runs clean.");
        prompt(prompts, "explain_latest_error", "Explain stacktale's most recent error and propose a fix.");
        return result;
    }

    private void prompt(ArrayNode prompts, String name, String description) {
        ObjectNode p = prompts.addObject();
        p.put("name", name);
        p.put("description", description);
        p.putArray("arguments"); // no arguments — these are ready-to-run
    }

    private JsonNode promptsGet(JsonNode params) {
        String name = params.path("name").asText();
        String text = switch (name) {
            case "fix_loop" -> FIX_LOOP_PROMPT;
            case "explain_latest_error" -> EXPLAIN_LATEST_PROMPT;
            default -> throw new IllegalArgumentException("unknown prompt: " + name);
        };
        ObjectNode result = JSON.createObjectNode();
        ObjectNode message = result.putArray("messages").addObject();
        message.put("role", "user");
        ObjectNode content = message.putObject("content");
        content.put("type", "text");
        content.put("text", text);
        return result;
    }

    private JsonNode toolsList() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        tools.add(tool("list_errors", "List error reports", true,
                "List stacktale error reports (newest first): id, timestamp, headline, repeat count.",
                "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\",\"description\":\"max entries, default 20\"}}}",
                REPORTS_SCHEMA));
        tools.add(tool("get_report", "Get a full report", true,
                "Get the full st/1 report block for one error id (story, fields, distilled stack, env).",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"description\":\"report id, e.g. c73cf755\"}},\"required\":[\"id\"]}",
                null));
        tools.add(tool("errors_since", "Errors since a time", true,
                "Full report blocks with timestamp >= the given moment (format: yyyy-MM-dd HH:mm:ss).",
                "{\"type\":\"object\",\"properties\":{\"since\":{\"type\":\"string\",\"description\":\"e.g. 2026-07-10 11:00:00\"}},\"required\":[\"since\"]}",
                REPORTS_SCHEMA));
        tools.add(tool("find_similar_errors", "Find similar errors", true,
                "Find past reports similar to an exception headline or stack snippet, ranked by root-cause type + digit-normalized message. Answers \"have we seen this before?\".",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"an exception headline or stack snippet, e.g. 'NullPointerException: customer is null'\"}},\"required\":[\"query\"]}",
                REPORTS_SCHEMA));
        // NOT idempotent: it advances the per-session cursor.
        tools.add(tool("errors_since_last_check", "Check for new errors", false,
                "The fix-loop primitive. First call shows the errors currently on file; after you re-run the app/tests, it reports what's 🆕 new or 🔁 still occurring since your last call — or '✓ No new errors' when it's clean. Call it each round of a fix→run→check loop until clean. Optional reset re-baselines.",
                "{\"type\":\"object\",\"properties\":{\"reset\":{\"type\":\"boolean\",\"description\":\"forget what was already reported and re-baseline from the current file\"}}}",
                LOOP_SCHEMA));
        tools.add(tool("match_report", "Match a pasted trace", true,
                "Paste a raw exception + stack trace and get the full stacktale report captured for it (story, fields, distilled stack, env) — matched by root-cause type and message. The bridge from a pasted trace to the agent having the whole context.",
                "{\"type\":\"object\",\"properties\":{\"trace\":{\"type\":\"string\",\"description\":\"a pasted exception and its stack trace\"}},\"required\":[\"trace\"]}",
                null));
        return result;
    }

    /** Every tool here only reads the local report file — advertise that so clients can auto-approve. */
    private JsonNode tool(String name, String title, boolean idempotent, String description,
                          String schemaJson, String outputSchemaJson) {
        try {
            ObjectNode tool = JSON.createObjectNode();
            tool.put("name", name);
            tool.put("description", description);
            tool.set("inputSchema", JSON.readTree(schemaJson));
            if (outputSchemaJson != null) tool.set("outputSchema", JSON.readTree(outputSchemaJson));
            ObjectNode annotations = tool.putObject("annotations");
            annotations.put("title", title);
            annotations.put("readOnlyHint", true);
            annotations.put("idempotentHint", idempotent);
            annotations.put("openWorldHint", false);
            return tool;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // Output schemas for the tools that also return structuredContent (a summary of reports;
    // the fix-loop tool adds a clean flag). get_report / match_report stay text-only.
    private static final String REPORTS_SCHEMA =
            "{\"type\":\"object\",\"required\":[\"reports\"],\"properties\":{\"reports\":{\"type\":\"array\","
            + "\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},"
            + "\"timestamp\":{\"type\":\"string\"},\"headline\":{\"type\":\"string\"},"
            + "\"repeats\":{\"type\":\"integer\"}}}}}}";
    private static final String LOOP_SCHEMA =
            "{\"type\":\"object\",\"required\":[\"clean\"],\"properties\":{\"clean\":{\"type\":\"boolean\"},"
            + "\"new\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},"
            + "\"headline\":{\"type\":\"string\"},\"repeats\":{\"type\":\"integer\"}}}},"
            + "\"recurring\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},"
            + "\"headline\":{\"type\":\"string\"},\"repeats\":{\"type\":\"integer\"}}}}}}";

    /** A tool's answer: the human text (always) and optional structuredContent for parsers. */
    private record ToolResult(String text, JsonNode structured) {
        static ToolResult text(String t) {
            return new ToolResult(t, null);
        }
    }

    private ArrayNode reportSummaries(List<StReport> reports, boolean withTimestamp) {
        ArrayNode arr = JSON.createArrayNode();
        for (StReport r : reports) {
            ObjectNode o = arr.addObject();
            o.put("id", r.id());
            if (withTimestamp) o.put("timestamp", r.timestamp());
            o.put("headline", r.headline());
            o.put("repeats", r.repeats());
        }
        return arr;
    }

    private JsonNode toolsCall(JsonNode params) throws IOException {
        String name = params.path("name").asText();
        JsonNode args = params.path("arguments");
        ToolResult tr = switch (name) {
            case "list_errors" -> listErrors(args.path("limit").asInt(20));
            case "get_report" -> ToolResult.text(getReport(args.path("id").asText()));
            case "errors_since" -> errorsSince(args.path("since").asText());
            case "find_similar_errors" -> findSimilar(args.path("query").asText());
            case "errors_since_last_check" -> errorsSinceLastCheck(args.path("reset").asBoolean(false));
            case "match_report" -> ToolResult.text(matchReport(args.path("trace").asText()));
            default -> throw new IllegalArgumentException("unknown tool: " + name);
        };
        ObjectNode result = JSON.createObjectNode();
        ObjectNode content = result.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", tr.text());
        if (tr.structured() != null) result.set("structuredContent", tr.structured());
        return result;
    }

    private ToolResult listErrors(int limit) throws IOException {
        List<StReport> all = reports.read();
        all.sort(Comparator.comparing(StReport::timestamp).reversed());
        List<StReport> shown = all.stream().limit(limit).toList();
        StringBuilder sb = new StringBuilder();
        if (all.isEmpty()) {
            sb.append("No error reports found.");
        } else {
            shown.forEach(r -> sb.append('#').append(r.id())
                    .append("  ").append(r.timestamp())
                    .append(r.repeats() > 1 ? "  (×" + r.repeats() + ")" : "")
                    .append("  ").append(r.headline()).append('\n'));
            if (all.size() > limit) sb.append("… ").append(all.size() - limit).append(" older reports (raise limit to see them)\n");
        }
        ObjectNode structured = JSON.createObjectNode();
        structured.set("reports", reportSummaries(shown, true));
        return new ToolResult(sb.toString(), structured);
    }

    private String getReport(String id) throws IOException {
        return reports.read().stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .map(r -> r.repeats() > 1 ? r.block() + "(occurred " + r.repeats() + "× in total)\n" : r.block())
                .orElse("No report with id '" + id + "'. Use list_errors to see available ids.");
    }

    private ToolResult errorsSince(String since) throws IOException {
        LocalDateTime cutoff = LocalDateTime.parse(since.replace('T', ' ').substring(0, 19),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        List<StReport> matching = reports.read().stream()
                .filter(r -> !StReportFile.parseTimestamp(r.timestamp()).isBefore(cutoff))
                .sorted(Comparator.comparing(StReport::timestamp))
                .toList();
        if (matching.isEmpty()) return ToolResult.text("No reports since " + since + ".");
        StringBuilder sb = new StringBuilder();
        matching.forEach(r -> sb.append(r.block()).append('\n'));
        ObjectNode structured = JSON.createObjectNode();
        structured.set("reports", reportSummaries(matching, true));
        return new ToolResult(sb.toString(), structured);
    }

    /**
     * The fix-loop primitive. The first call baselines against the current file and returns
     * the errors already there (the ones to fix). Every later call reports only what changed
     * since it last ran — a brand-new fingerprint, or an already-seen one that occurred again
     * — and says so plainly when nothing did, which is the loop's "clean" signal.
     */
    private synchronized ToolResult errorsSinceLastCheck(boolean reset) throws IOException {
        if (reset) {
            seenRepeats.clear();
            baselineTaken = false;
        }
        List<StReport> all = reports.read();
        all.sort(Comparator.comparing(StReport::timestamp).reversed());

        if (!baselineTaken) {
            baselineTaken = true;
            all.forEach(r -> seenRepeats.put(r.id(), r.repeats()));
            String text;
            if (all.isEmpty()) {
                text = "✓ No errors on file.";
            } else {
                StringBuilder sb = new StringBuilder(all.size() + " error(s) currently on file — start here:\n");
                all.forEach(r -> appendErrorLine(sb, r));
                sb.append("\nFix, re-run your app or tests, then call errors_since_last_check again to see what changed.");
                text = sb.toString();
            }
            // the current errors are what's "new to you"; clean only if there are none
            return new ToolResult(text, loopStructured(all.isEmpty(), all, List.of()));
        }

        List<StReport> fresh = new ArrayList<>();
        List<StReport> recurring = new ArrayList<>();
        for (StReport r : all) {
            Integer prev = seenRepeats.get(r.id());
            if (prev == null) fresh.add(r);
            else if (r.repeats() > prev) recurring.add(r);
            seenRepeats.put(r.id(), r.repeats());
        }
        boolean clean = fresh.isEmpty() && recurring.isEmpty();
        String text;
        if (clean) {
            text = "✓ No new errors since your last check.";
        } else {
            StringBuilder sb = new StringBuilder();
            if (!fresh.isEmpty()) {
                sb.append("🆕 ").append(fresh.size()).append(" new:\n");
                fresh.forEach(r -> appendErrorLine(sb, r));
            }
            if (!recurring.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("🔁 ").append(recurring.size()).append(" still occurring (a fix didn't take):\n");
                recurring.forEach(r -> appendErrorLine(sb, r));
            }
            sb.append("\nget_report <id> for the full block.");
            text = sb.toString();
        }
        return new ToolResult(text, loopStructured(clean, fresh, recurring));
    }

    private JsonNode loopStructured(boolean clean, List<StReport> fresh, List<StReport> recurring) {
        ObjectNode structured = JSON.createObjectNode();
        structured.put("clean", clean);
        structured.set("new", reportSummaries(fresh, false));
        structured.set("recurring", reportSummaries(recurring, false));
        return structured;
    }

    private static void appendErrorLine(StringBuilder sb, StReport r) {
        sb.append('#').append(r.id()).append("  ").append(r.headline());
        if (r.repeats() > 1) sb.append("  (×").append(r.repeats()).append(')');
        sb.append('\n');
    }

    /**
     * Auto-attach: take a raw pasted exception + stack and return the full report stacktale
     * already captured for it — the bridge from "a human pasted a trace" to "the agent has
     * the whole story". Matches on the root cause (the deepest {@code Caused by:}), reusing
     * the similarity ranking, and returns the best block plus any close runners-up.
     */
    private String matchReport(String trace) throws IOException {
        if (trace == null || trace.isBlank()) {
            return "Paste an exception with its stack trace to match it against captured reports.";
        }
        List<StReport> ranked = rank(rootCauseLine(trace), reports.read(), 3);
        if (ranked.isEmpty()) {
            return "No captured report matches that trace. (list_errors shows what's on file.)";
        }
        StReport best = ranked.get(0);
        StringBuilder sb = new StringBuilder("Closest captured report #" + best.id()
                + " (matched on root-cause type + message):\n\n");
        sb.append(best.repeats() > 1 ? best.block() + "(occurred " + best.repeats() + "× in total)\n" : best.block());
        if (ranked.size() > 1) {
            List<String> others = ranked.stream().skip(1).map(r -> "#" + r.id()).toList();
            sb.append("\nOther candidates: ").append(String.join(", ", others))
                    .append(" — get_report for those.");
        }
        return sb.toString();
    }

    /** The root-cause line of a raw trace: the last {@code Caused by:}, else the top exception line. */
    private static String rootCauseLine(String trace) {
        String root = null;
        for (String line : trace.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("Caused by:")) {
                root = t.substring("Caused by:".length()).strip();
            } else if (root == null && !t.isEmpty() && !t.startsWith("at ") && !t.startsWith("...")) {
                root = t; // first non-frame line = the thrown exception (used when there's no Caused-by)
            }
        }
        return root == null ? trace : root;
    }

    private ToolResult findSimilar(String query) throws IOException {
        if (query == null || query.isBlank()) return ToolResult.text("Provide an exception headline or stack snippet to match against.");
        List<StReport> ranked = rank(query, reports.read(), 5);
        if (ranked.isEmpty()) return ToolResult.text("No similar reports found.");
        StringBuilder sb = new StringBuilder("Most similar reports first:\n");
        ranked.forEach(r -> sb.append('#').append(r.id())
                .append("  ").append(r.timestamp())
                .append(r.repeats() > 1 ? "  (×" + r.repeats() + ")" : "")
                .append("  ").append(r.headline()).append('\n'));
        sb.append("\nUse get_report <id> for the full block.");
        ObjectNode structured = JSON.createObjectNode();
        structured.set("reports", reportSummaries(ranked, true));
        return new ToolResult(sb.toString(), structured);
    }

    /** Reports ranked by similarity to {@code query} (an exception headline or stack snippet). */
    static List<StReport> rank(String query, List<StReport> reports, int limit) {
        Sig q = Sig.of(query);
        return reports.stream()
                .map(r -> new Scored(r, score(q, Sig.of(r.headline()))))
                .filter(sc -> sc.score() > 0)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(limit)
                .map(Scored::report)
                .toList();
    }

    private record Scored(StReport report, double score) {}

    /** An error's similarity signature: the exception type + its digit-normalized message words. */
    private record Sig(String type, Set<String> words) {
        static Sig of(String headlineOrQuery) {
            String line = headlineOrQuery == null ? "" : headlineOrQuery.strip();
            int nl = line.indexOf('\n');
            if (nl >= 0) line = line.substring(0, nl).strip();          // first line of a snippet
            String type = "";
            String message = line;
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).matches("[\\w.$]+")) {
                String head = line.substring(0, colon);
                type = head.substring(head.lastIndexOf('.') + 1);       // simple exception name
                message = line.substring(colon + 1);
            }
            Set<String> words = new LinkedHashSet<>();
            for (String w : message.toLowerCase().replaceAll("\\d+", "0").split("[^a-z0]+")) {
                if (w.length() > 2) words.add(w);                       // drop very short tokens
            }
            return new Sig(type, words);
        }
    }

    /** 0 = nothing shared; higher = more similar (same type + overlapping normalized message). */
    private static double score(Sig q, Sig r) {
        double s = 0;
        if (!q.type().isEmpty() && q.type().equalsIgnoreCase(r.type())) s += 3;
        if (!q.words().isEmpty() && !r.words().isEmpty()) {
            Set<String> shared = new LinkedHashSet<>(q.words());
            shared.retainAll(r.words());
            Set<String> union = new LinkedHashSet<>(q.words());
            union.addAll(r.words());
            s += 3.0 * shared.size() / union.size();                    // weighted Jaccard
        }
        return s;
    }
}
