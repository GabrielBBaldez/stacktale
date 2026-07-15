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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
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

    private static final String PROTOCOL_VERSION = "2024-11-05";
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
            case "initialize" -> initialize();
            case "tools/list" -> toolsList();
            case "tools/call" -> toolsCall(params);
            case "resources/list" -> resourcesList();
            case "resources/read" -> resourcesRead(params);
            case "resources/subscribe" -> resourcesSubscribe();
            case "resources/unsubscribe" -> resourcesUnsubscribe();
            case "ping" -> JSON.createObjectNode();
            default -> throw new UnknownMethodException("unknown method: " + method);
        };
    }

    private JsonNode initialize() {
        ObjectNode result = JSON.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        // resources with subscribe: the AI is TOLD when a new error lands, it doesn't poll
        ObjectNode res = capabilities.putObject("resources");
        res.put("subscribe", true);
        res.put("listChanged", false);
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "stacktale");
        info.put("version", "0.4.0");
        return result;
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
        contents.put("text", listErrors(50)); // the "what's there now" view; get_report for full blocks
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

    private JsonNode toolsList() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        tools.add(tool("list_errors",
                "List stacktale error reports (newest first): id, timestamp, headline, repeat count.",
                "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\",\"description\":\"max entries, default 20\"}}}"));
        tools.add(tool("get_report",
                "Get the full st/1 report block for one error id (story, fields, distilled stack, env).",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"description\":\"report id, e.g. c73cf755\"}},\"required\":[\"id\"]}"));
        tools.add(tool("errors_since",
                "Full report blocks with timestamp >= the given moment (format: yyyy-MM-dd HH:mm:ss).",
                "{\"type\":\"object\",\"properties\":{\"since\":{\"type\":\"string\",\"description\":\"e.g. 2026-07-10 11:00:00\"}},\"required\":[\"since\"]}"));
        tools.add(tool("find_similar_errors",
                "Find past reports similar to an exception headline or stack snippet, ranked by root-cause type + digit-normalized message. Answers \"have we seen this before?\".",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"an exception headline or stack snippet, e.g. 'NullPointerException: customer is null'\"}},\"required\":[\"query\"]}"));
        return result;
    }

    private JsonNode tool(String name, String description, String schemaJson) {
        try {
            ObjectNode tool = JSON.createObjectNode();
            tool.put("name", name);
            tool.put("description", description);
            tool.set("inputSchema", JSON.readTree(schemaJson));
            return tool;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode toolsCall(JsonNode params) throws IOException {
        String name = params.path("name").asText();
        JsonNode args = params.path("arguments");
        String text = switch (name) {
            case "list_errors" -> listErrors(args.path("limit").asInt(20));
            case "get_report" -> getReport(args.path("id").asText());
            case "errors_since" -> errorsSince(args.path("since").asText());
            case "find_similar_errors" -> findSimilar(args.path("query").asText());
            default -> throw new IllegalArgumentException("unknown tool: " + name);
        };
        ObjectNode result = JSON.createObjectNode();
        ObjectNode content = result.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", text);
        return result;
    }

    private String listErrors(int limit) throws IOException {
        List<StReport> all = reports.read();
        if (all.isEmpty()) return "No error reports found.";
        all.sort(Comparator.comparing(StReport::timestamp).reversed());
        StringBuilder sb = new StringBuilder();
        all.stream().limit(limit).forEach(r -> sb.append('#').append(r.id())
                .append("  ").append(r.timestamp())
                .append(r.repeats() > 1 ? "  (×" + r.repeats() + ")" : "")
                .append("  ").append(r.headline()).append('\n'));
        if (all.size() > limit) sb.append("… ").append(all.size() - limit).append(" older reports (raise limit to see them)\n");
        return sb.toString();
    }

    private String getReport(String id) throws IOException {
        return reports.read().stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .map(r -> r.repeats() > 1 ? r.block() + "(occurred " + r.repeats() + "× in total)\n" : r.block())
                .orElse("No report with id '" + id + "'. Use list_errors to see available ids.");
    }

    private String errorsSince(String since) throws IOException {
        LocalDateTime cutoff = LocalDateTime.parse(since.replace('T', ' ').substring(0, 19),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        List<StReport> matching = reports.read().stream()
                .filter(r -> !StReportFile.parseTimestamp(r.timestamp()).isBefore(cutoff))
                .sorted(Comparator.comparing(StReport::timestamp))
                .toList();
        if (matching.isEmpty()) return "No reports since " + since + ".";
        StringBuilder sb = new StringBuilder();
        matching.forEach(r -> sb.append(r.block()).append('\n'));
        return sb.toString();
    }

    private String findSimilar(String query) throws IOException {
        if (query == null || query.isBlank()) return "Provide an exception headline or stack snippet to match against.";
        List<StReport> ranked = rank(query, reports.read(), 5);
        if (ranked.isEmpty()) return "No similar reports found.";
        StringBuilder sb = new StringBuilder("Most similar reports first:\n");
        ranked.forEach(r -> sb.append('#').append(r.id())
                .append("  ").append(r.timestamp())
                .append(r.repeats() > 1 ? "  (×" + r.repeats() + ")" : "")
                .append("  ").append(r.headline()).append('\n'));
        sb.append("\nUse get_report <id> for the full block.");
        return sb.toString();
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
