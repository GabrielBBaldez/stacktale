package io.github.gabrielbbaldez.stacktale;

import org.slf4j.helpers.MessageFormatter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The st-json/1 renderer: one compact JSON object per line (NDJSON). Where {@link
 * ReportRenderer} optimises for an LLM/human to <em>read</em> (densest tokens win), this
 * optimises for a program to <em>parse</em> — every st/1 section becomes an addressable
 * field ({@code error.type}, {@code fields.orderId}, {@code recurrence.count}), so a
 * pipeline, dashboard or agent can query without scraping text.
 *
 * <p>Same redaction as the text format: secret-named keys and secret-position args are
 * masked, values pass through the {@link Redactor}. Multi-line values keep their newlines
 * (JSON escapes them) instead of being flattened. Reports, repeats, session markers and
 * storm lines all share a {@code "type"} discriminator; a {@code header} line names the
 * format version.
 */
final class JsonReportRenderer implements Renderer {

    static final String FORMAT_VERSION = "st-json/1";

    private static final int MAX_ARGS = 8;
    private static final int MAX_ARG_LENGTH = 80;

    private final DateTimeFormatter iso;
    private final Redactor redactor;

    JsonReportRenderer(ZoneId zone, Redactor redactor) {
        // fixed-precision millis (.SSS) + offset ('Z' for UTC): a predictable width beats
        // ISO_OFFSET_DATE_TIME, which drops the fraction when it is .000
        this.iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(zone);
        this.redactor = redactor;
    }

    @Override
    public String render(Report r) {
        StringBuilder b = new StringBuilder(768);
        b.append("{\"type\":\"report\"");
        b.append(",\"id\":").append(quote(nz(r.id())));
        b.append(",\"ts\":").append(quote(iso(r.epochMillis())));
        b.append(",\"thread\":").append(quote(nz(r.threadName())));
        b.append(",\"error\":").append(errorObj(r));
        b.append(",\"log\":").append(logObj(r));
        appendIfPresent(b, "mdc", mapObj(r.mdc()));
        appendIfPresent(b, "fields", mapObj(r.fields()));
        appendIfPresent(b, "captured", cleanArr(r.captured()));
        if (r.occurrences() > 1) {
            b.append(",\"recurrence\":{\"count\":").append(r.occurrences())
                    .append(",\"firstSeen\":").append(quote(iso(r.firstSeenMillis()))).append('}');
        }
        appendIfPresent(b, "story", storyObj(r.story(), r.epochMillis()));
        if (r.stack() != null) b.append(",\"stack\":").append(stackObj(r.stack()));
        if (r.envLine() != null && !r.envLine().isBlank()) {
            b.append(",\"env\":").append(quote(r.envLine()));
        }
        return b.append("}\n").toString();
    }

    @Override
    public String renderSummary(String id, int count, long lastMillis) {
        return "{\"type\":\"repeat\",\"id\":" + quote(nz(id)) + ",\"count\":" + count
                + ",\"last\":" + quote(iso(lastMillis)) + "}\n";
    }

    @Override
    public String fileHeader() {
        return "{\"type\":\"header\",\"format\":\"" + FORMAT_VERSION
                + "\",\"docs\":\"https://github.com/GabrielBBaldez/stacktale/blob/main/docs/FORMAT.md\"}\n";
    }

    @Override
    public String sessionMarker(long epochMillis, long pid) {
        return "{\"type\":\"session\",\"ts\":" + quote(iso(epochMillis)) + ",\"pid\":" + pid + "}\n";
    }

    @Override
    public String stormLine(int suppressed, int limit) {
        return "{\"type\":\"storm\",\"suppressed\":" + suppressed + ",\"limit\":" + limit + "}\n";
    }

    private String errorObj(Report r) {
        DistilledStack s = r.stack();
        if (s == null) {
            String msg = MessageFormatter.arrayFormat(r.messagePattern(), r.args()).getMessage();
            return "{\"noException\":true,\"message\":" + quote(redactor.redact(nz(msg))) + "}";
        }
        StringBuilder b = new StringBuilder("{\"type\":").append(quote(nz(s.rootType())));
        if (s.rootMessage() != null && !s.rootMessage().isBlank()) {
            b.append(",\"message\":").append(quote(redactor.redact(s.rootMessage())));
        }
        if (s.culpritLine() != null) {
            b.append(",\"culprit\":{\"frame\":").append(quote(s.culpritLine()))
                    .append(",\"appCode\":").append(s.culpritIsAppCode()).append('}');
        }
        if (s.wrappedBy() != null && !s.wrappedBy().isEmpty()) {
            b.append(",\"wrappedBy\":").append(cleanArr(s.wrappedBy()));
        }
        return b.append('}').toString();
    }

    private String logObj(Report r) {
        StringBuilder b = new StringBuilder("{\"pattern\":")
                .append(quote(redactor.redact(nz(r.messagePattern()))));
        String args = argsArr(r.messagePattern(), r.args());
        if (args != null) b.append(",\"args\":").append(args);
        return b.append(",\"logger\":").append(quote(nz(r.loggerName()))).append('}').toString();
    }

    /** Arg values, with secret-position args masked and poisonous toString() survived — mirrors the text renderer. */
    private String argsArr(String pattern, Object[] args) {
        if (args == null || args.length == 0) return null;
        java.util.Set<Integer> secret = redactor.secretArgIndexes(pattern);
        StringBuilder b = new StringBuilder("[");
        int shown = Math.min(args.length, MAX_ARGS);
        for (int i = 0; i < shown; i++) {
            if (i > 0) b.append(',');
            if (secret.contains(i)) {
                b.append(quote("███"));
                continue;
            }
            String s;
            try {
                s = String.valueOf(args[i]);
            } catch (Throwable t) {
                s = "<toString failed: " + t.getClass().getSimpleName() + ">";
            }
            s = redactor.redact(s);
            b.append(quote(s.length() > MAX_ARG_LENGTH ? s.substring(0, MAX_ARG_LENGTH) + "…" : s));
        }
        if (args.length > MAX_ARGS) b.append(',').append(quote("…+" + (args.length - MAX_ARGS)));
        return b.append(']').toString();
    }

    private String mapObj(Map<String, String> map) {
        if (map == null || map.isEmpty()) return null;
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(map).entrySet()) {
            if (!first) b.append(',');
            // a secret-named key masks its value; otherwise the value is redacted normally
            String v = redactor.isSecretKey(e.getKey()) ? "███" : redactor.redact(nz(e.getValue()));
            b.append(quote(e.getKey())).append(':').append(quote(v));
            first = false;
        }
        return b.append('}').toString();
    }

    private String storyObj(Story story, long errorEpoch) {
        if (story == null || story.entries() == null || story.entries().isEmpty()) return null;
        List<StoryEntry> events = story.entries();
        int errorIdx = -1;
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).epochMillis() == errorEpoch) {
                errorIdx = i;
                break;
            }
        }
        StringBuilder b = new StringBuilder("{\"label\":").append(quote(nz(story.contextLabel())));
        if (story.omittedByAge() > 0) b.append(",\"omittedByAge\":").append(story.omittedByAge());
        b.append(",\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            StoryEntry e = events.get(i);
            if (i > 0) b.append(',');
            b.append("{\"ts\":").append(quote(iso(e.epochMillis())))
                    .append(",\"level\":").append(quote(nz(e.level())))
                    .append(",\"logger\":").append(quote(nz(e.logger())))
                    .append(",\"message\":").append(quote(redactor.redact(nz(e.message()))));
            if (i == errorIdx) b.append(",\"thisError\":true");
            b.append('}');
        }
        return b.append("]}").toString();
    }

    private String stackObj(DistilledStack s) {
        StringBuilder b = new StringBuilder("{\"shown\":").append(s.shownFrames())
                .append(",\"total\":").append(s.totalFrames())
                .append(",\"frames\":").append(arr(s.frameLines(), false));
        if (s.suppressed() != null && !s.suppressed().isEmpty()) {
            b.append(",\"suppressed\":").append(arr(s.suppressed(), true));
        }
        return b.append('}').toString();
    }

    // --- helpers ---

    private static void appendIfPresent(StringBuilder b, String key, String jsonValue) {
        if (jsonValue != null) b.append(",\"").append(key).append("\":").append(jsonValue);
    }

    private String cleanArr(List<String> items) {
        return items == null || items.isEmpty() ? null : arr(items, true);
    }

    private String arr(List<String> items, boolean redact) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(',');
            String s = nz(items.get(i));
            b.append(quote(redact ? redactor.redact(s) : s));
        }
        return b.append(']').toString();
    }

    private String iso(long epochMillis) {
        return iso.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** JSON-escape and wrap in quotes. Newlines are escaped (not flattened), keeping structure. */
    private static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
