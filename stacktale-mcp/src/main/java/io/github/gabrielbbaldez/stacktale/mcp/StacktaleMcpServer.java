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
import java.util.List;

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

    StacktaleMcpServer(Path file) {
        this.reports = new StReportFile(file);
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of("errors-ai.log");
        for (int i = 0; i < args.length - 1; i++) {
            if ("--file".equals(args[i])) file = Path.of(args[i + 1]);
        }
        new StacktaleMcpServer(file).serve(System.in, System.out);
    }

    void serve(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        PrintStream writer = new PrintStream(out, true, StandardCharsets.UTF_8);
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
            writer.println(JSON.writeValueAsString(response));
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
            case "ping" -> JSON.createObjectNode();
            default -> throw new UnknownMethodException("unknown method: " + method);
        };
    }

    private JsonNode initialize() {
        ObjectNode result = JSON.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.putObject("capabilities").putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "stacktale");
        info.put("version", "0.3.0");
        return result;
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
}
