package io.github.gabrielbbaldez.stacktale.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses st/1 report files (the main file plus rotated {@code .N} backups) into report
 * entries an MCP tool can serve. Read-only; re-reads on every call — the files are small
 * by design and freshness beats caching for a debugging tool.
 */
final class StReportFile {

    record StReport(String id, String timestamp, String headline, int repeats, String block) {}

    private static final Pattern HEADER = Pattern.compile(
            "^━━━ ERROR #(\\w+) ━━━ (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) ");
    private static final Pattern REPEAT = Pattern.compile("^━ #(\\w+) repeated (\\d+)× ");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Path file;

    StReportFile(Path file) {
        this.file = file;
    }

    List<StReport> read() throws IOException {
        Map<String, StReport> byId = new LinkedHashMap<>();
        // oldest backup first so the main file's newer data wins ordering naturally
        List<Path> sources = new ArrayList<>();
        // scan contiguous .N backups (ReportWriter's maxBackups is unbounded); stop at the
        // first gap so a huge configured retention doesn't mean scanning thousands of paths
        List<Path> backups = new ArrayList<>();
        for (int i = 1; ; i++) {
            Path backup = file.resolveSibling(file.getFileName() + "." + i);
            if (!Files.exists(backup)) break;
            backups.add(backup);
        }
        for (int i = backups.size() - 1; i >= 0; i--) sources.add(backups.get(i)); // oldest first
        if (Files.exists(file)) sources.add(file);

        for (Path source : sources) {
            parse(Files.readString(source, StandardCharsets.UTF_8), byId);
        }
        return new ArrayList<>(byId.values());
    }

    private void parse(String content, Map<String, StReport> byId) {
        String[] lines = content.split("\n", -1);
        StringBuilder block = null;
        String id = null;
        String timestamp = null;
        String headline = null;
        for (String line : lines) {
            Matcher header = HEADER.matcher(line);
            if (header.find()) {
                block = new StringBuilder(line).append('\n');
                id = header.group(1);
                timestamp = header.group(2);
                headline = null;
                continue;
            }
            if (block != null) {
                block.append(line).append('\n');
                if (headline == null && !line.isBlank()) headline = line;
                if (line.startsWith("━━━ END #")) {
                    byId.put(id, new StReport(id, timestamp, headline, 1, block.toString()));
                    block = null;
                }
                continue;
            }
            Matcher repeat = REPEAT.matcher(line);
            if (repeat.find()) {
                StReport existing = byId.get(repeat.group(1));
                if (existing != null) {
                    byId.put(existing.id(), new StReport(existing.id(), existing.timestamp(),
                            existing.headline(), Integer.parseInt(repeat.group(2)), existing.block()));
                }
            }
        }
    }

    static LocalDateTime parseTimestamp(String ts) {
        return LocalDateTime.parse(ts, TS);
    }
}
