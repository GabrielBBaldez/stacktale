package io.github.gabrielbbaldez.stacktale;

import java.util.function.BiConsumer;

/**
 * Funnels uncaught exceptions through the normal logging pipeline (logger
 * {@code stacktale.uncaught}), so plain-Java apps get reports for exceptions that never
 * reach a {@code log.error}. Wraps and preserves any pre-existing default handler.
 *
 * <p>The sink is provided by the logging backend ("log this message + throwable at ERROR
 * through logger {@code stacktale.uncaught}") so the handler works identically for
 * Logback and Log4j2 hosts.
 */
public final class UncaughtHandler implements Thread.UncaughtExceptionHandler {

    /** Logger name backends must route the sink through — the pipeline processes it normally. */
    public static final String UNCAUGHT_LOGGER = "stacktale.uncaught";

    private final Thread.UncaughtExceptionHandler previous;
    private final BiConsumer<String, Throwable> errorSink;

    UncaughtHandler(Thread.UncaughtExceptionHandler previous, BiConsumer<String, Throwable> errorSink) {
        this.previous = previous;
        this.errorSink = errorSink;
    }

    /** Idempotent; the sink logs (message, throwable) at ERROR via logger {@link #UNCAUGHT_LOGGER}. */
    public static void install(BiConsumer<String, Throwable> errorSink) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof UncaughtHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtHandler(current, errorSink));
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            errorSink.accept("Uncaught exception in thread " + t.getName(), e);
        } catch (Throwable ignored) {
            // never make an uncaught exception worse
        }
        if (previous != null) {
            previous.uncaughtException(t, e);
        } else if (t.getThreadGroup() != null) {
            t.getThreadGroup().uncaughtException(t, e); // JVM default behavior: print to stderr
        }
    }
}
