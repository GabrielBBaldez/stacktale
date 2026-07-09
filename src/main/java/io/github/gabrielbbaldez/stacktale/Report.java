package io.github.gabrielbbaldez.stacktale;

import java.util.Map;

/** Everything the renderer needs to produce one st/1 error report block. */
public record Report(
        String id,
        long epochMillis,
        String threadName,
        DistilledStack stack,
        String messagePattern,
        Object[] args,
        String loggerName,
        Map<String, String> mdc,
        Map<String, String> fields,
        Story story,
        String envLine
) {}
