package io.github.gabrielbbaldez.stacktale;

import java.util.Map;

/**
 * stacktale's own view of one log event — the seam between logging frameworks and the
 * report pipeline. Each backend (Logback, Log4j2) adapts its native event to this,
 * defensively: {@code formattedMessage} is already rendered, {@code mdc} is never null
 * and never throws, {@code throwable} is the real one (not a serialization proxy).
 */
public record LogEventData(
        long epochMillis,
        String level,
        boolean error,
        String loggerName,
        String threadName,
        String messagePattern,
        Object[] args,
        String formattedMessage,
        Map<String, String> mdc,
        Throwable throwable
) {}
