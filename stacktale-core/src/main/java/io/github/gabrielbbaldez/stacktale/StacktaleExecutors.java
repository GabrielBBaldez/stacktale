package io.github.gabrielbbaldez.stacktale;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Carries the story across thread hops. stacktale correlates the story through MDC keys
 * ({@code traceId}, ...), so all an async boundary needs is MDC propagation — these
 * helpers capture the submitting thread's MDC and restore it inside the task.
 *
 * <pre>
 * ExecutorService pool = Executors.newFixedThreadPool(8);
 * CompletableFuture.supplyAsync(StacktaleExecutors.wrap(() -> chargeCard(order)),
 *                               StacktaleExecutors.wrap(pool));
 * </pre>
 *
 * Apps already using a context-propagation library (Micrometer, Reactor) don't need
 * this — any mechanism that moves the MDC across the hop keeps the story whole.
 */
public final class StacktaleExecutors {

    private StacktaleExecutors() {}

    /** Every task submitted through the returned executor sees the submitter's MDC. */
    public static Executor wrap(Executor delegate) {
        return command -> delegate.execute(wrap(command));
    }

    public static Runnable wrap(Runnable task) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            apply(mdc);
            try {
                task.run();
            } finally {
                apply(previous);
            }
        };
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            apply(mdc);
            try {
                return task.call();
            } finally {
                apply(previous);
            }
        };
    }

    public static <T> Supplier<T> wrap(Supplier<T> task) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            apply(mdc);
            try {
                return task.get();
            } finally {
                apply(previous);
            }
        };
    }

    private static void apply(Map<String, String> mdc) {
        if (mdc == null || mdc.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(mdc);
        }
    }
}
