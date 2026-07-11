package io.github.gabrielbbaldez.stacktale;

/**
 * Turns pipeline entries into the bytes appended to the report file. Two implementations:
 * {@link ReportRenderer} (the st/1 text format, the default — densest for an LLM to read)
 * and {@link JsonReportRenderer} (st-json/1 NDJSON, for programmatic parsers/pipelines).
 * Both produce entries ending in {@code \n}; the pipeline is oblivious to which is active.
 */
interface Renderer {

    /** A full error report. */
    String render(Report report);

    /** The "same error repeated N×" line emitted instead of a fresh report within the dedup window. */
    String renderSummary(String id, int count, long lastMillis);

    /** The self-describing header written once at the top of a (rotated) file. */
    String fileHeader();

    /** The marker separating application runs that share a file. */
    String sessionMarker(long epochMillis, long pid);

    /** The acknowledgement that a flood of distinct errors was rate-limited. */
    String stormLine(int suppressed, int limit);
}
