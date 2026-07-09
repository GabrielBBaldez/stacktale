package io.github.gabrielbbaldez.stacktale;

import org.slf4j.helpers.MessageFormatter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TreeMap;

/**
 * Pure formatter for the st/1 report format. The output of this class is a public,
 * versioned API — golden-file tests pin it, and changes mean a format version bump.
 */
final class ReportRenderer {

    static final String FORMAT_VERSION = "st/1";

    private static final int MAX_ARGS = 8;
    private static final int MAX_ARG_LENGTH = 80;
    private static final int MAX_LOGGER_PAD = 20;

    private final DateTimeFormatter dateTime;
    private final DateTimeFormatter time;

    ReportRenderer(ZoneId zone) {
        this.dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone);
        this.time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(zone);
    }

    String render(Report r) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("━━━ ERROR #").append(r.id()).append(" ━━━ ")
                .append(dateTime.format(Instant.ofEpochMilli(r.epochMillis())))
                .append(" thread=").append(r.threadName()).append(" ━━━\n");

        DistilledStack stack = r.stack();
        if (stack != null) {
            sb.append(stack.rootType());
            if (stack.rootMessage() != null && !stack.rootMessage().isBlank()) {
                sb.append(": ").append(flat(stack.rootMessage()));
            }
            sb.append('\n');
            if (stack.culpritLine() != null) {
                sb.append("at ").append(stack.culpritLine()).append(" ← YOUR CODE\n");
            }
            for (String w : stack.wrappedBy()) {
                sb.append("wrapped by: ").append(flat(w)).append('\n');
            }
        } else {
            sb.append("ERROR (no exception): ")
                    .append(flat(MessageFormatter.arrayFormat(r.messagePattern(), r.args()).getMessage()))
                    .append('\n');
        }

        sb.append("log: \"").append(flat(r.messagePattern())).append('"');
        String args = renderArgs(r.args());
        if (!args.isEmpty()) sb.append(" args=[").append(args).append(']');
        sb.append(" logger=").append(abbreviate(r.loggerName())).append('\n');

        if (r.mdc() != null && !r.mdc().isEmpty()) {
            sb.append("mdc:");
            new TreeMap<>(r.mdc()).forEach((k, v) -> sb.append(' ').append(flat(k)).append('=').append(flat(v)));
            sb.append('\n');
        }

        if (r.fields() != null && !r.fields().isEmpty()) {
            sb.append("fields:");
            new TreeMap<>(r.fields()).forEach((k, v) -> sb.append(' ').append(flat(k)).append('=').append(flat(v)));
            sb.append('\n');
        }

        renderStory(sb, r);

        if (stack != null) {
            sb.append('\n');
            sb.append("stack (distilled, ").append(stack.shownFrames()).append(" of ")
                    .append(stack.totalFrames()).append(" frames):\n");
            for (String line : stack.frameLines()) sb.append("  ").append(line).append('\n');
            for (String line : stack.suppressed()) sb.append("  ").append(flat(line)).append('\n');
        }

        sb.append('\n');
        sb.append("env: ").append(r.envLine()).append('\n');
        sb.append("━━━ END #").append(r.id()).append(" ━━━\n");
        return sb.toString();
    }

    String renderSummary(String id, int count, long lastMillis) {
        return "━ #" + id + " repeated " + count + "× (last "
                + time.format(Instant.ofEpochMilli(lastMillis)) + ") ━\n";
    }

    String fileHeader() {
        return """
                # AI-oriented error reports (format st/1, https://github.com/GabrielBBaldez/stacktale)
                # Each report is delimited by "━━━ ERROR #<id> ━━━" ... "━━━ END #<id> ━━━".
                # Sections: headline (root cause first), at (culprit frame), log, mdc,
                # fields (state carried by the exception's own getters/fields),
                # story (events leading up to and including the error, oldest first),
                # stack (distilled; framework frames collapsed), env. "← YOUR CODE" marks app frames.
                # Repeated errors append "━ #<id> repeated N× ━" lines instead of new reports.
                # "─── app start … ───" lines mark application restarts.
                """;
    }

    String sessionMarker(long epochMillis, long pid) {
        return "─── app start " + dateTime.format(Instant.ofEpochMilli(epochMillis))
                + " (pid " + pid + ") ───\n";
    }

    private void renderStory(StringBuilder sb, Report r) {
        List<StoryEntry> entries = r.story() == null ? List.of() : r.story().entries();
        if (entries.isEmpty()) return;

        sb.append('\n');
        long span = entries.get(entries.size() - 1).epochMillis() - entries.get(0).epochMillis();
        sb.append("story (").append(r.story().contextLabel()).append(", last ").append(entries.size())
                .append(entries.size() == 1 ? " event, " : " events, ").append(span).append("ms):\n");

        int loggerPad = Math.min(MAX_LOGGER_PAD,
                entries.stream().mapToInt(e -> e.logger().length()).max().orElse(0));

        int errorIdx = -1;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).epochMillis() == r.epochMillis()) {
                errorIdx = i;
                break;
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            StoryEntry e = entries.get(i);
            sb.append("  ").append(time.format(Instant.ofEpochMilli(e.epochMillis())))
                    .append(' ').append(pad(e.level(), 5))
                    .append(' ').append(pad(e.logger(), loggerPad))
                    .append("  ").append(flat(e.message()));
            if (i == errorIdx) sb.append("   ← this error");
            sb.append('\n');
        }
    }

    private static String renderArgs(Object[] args) {
        if (args == null || args.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(args.length, MAX_ARGS);
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(", ");
            String s;
            try {
                s = String.valueOf(args[i]);
            } catch (Throwable t) {
                // user objects may have poisonous toString(); the report must survive them
                s = "<toString failed: " + t.getClass().getSimpleName() + ">";
            }
            s = flat(s);
            sb.append(s.length() > MAX_ARG_LENGTH ? s.substring(0, MAX_ARG_LENGTH) + "…" : s);
        }
        if (args.length > MAX_ARGS) sb.append(", …+").append(args.length - MAX_ARGS);
        return sb.toString();
    }

    /** One line per section is part of the format: embedded newlines become literal {@code \n}. */
    private static String flat(String s) {
        if (s == null) return "";
        if (s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return s.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    private static String abbreviate(String loggerName) {
        String[] parts = loggerName.split("\\.");
        if (parts.length <= 1) return loggerName;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].isEmpty()) sb.append(parts[i].charAt(0)).append('.');
        }
        return sb.append(parts[parts.length - 1]).toString();
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
