package io.github.gabrielbbaldez.stacktale.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Subprocess entry point for {@link AgentCoexistenceIT}. Launched with the OpenTelemetry
 * javaagent and stacktale-agent both attached: logs one error whose throw-site arguments
 * stacktale-agent should capture, then times a tight throw/catch loop so the IT can report
 * the combined throw-path cost. Prints machine-readable markers on stdout.
 */
public final class CoexistenceApp {

    public static void main(String[] args) throws Exception {
        Path report = Path.of(args[0]);

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setFile(report.toString());
        appender.setAppPackages("io.github.gabrielbbaldez.stacktale.agent");
        appender.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);

        org.slf4j.Logger log = LoggerFactory.getLogger(CoexistenceApp.class);
        try {
            charge(889, 149.90);
        } catch (RuntimeException e) {
            log.error("charge failed for order {}", 889, e);
        }
        appender.stop(); // flush

        System.out.println("THROW_PATH_NANOS=" + measureThrowPath());
        System.out.println("COEXIST_DONE");
    }

    static void charge(int orderId, double amount) {
        throw new IllegalStateException("payment gateway refused order " + orderId);
    }

    /** Average nanos of one throw+catch through an instrumented method (capture runs on exit). */
    private static long measureThrowPath() {
        int iterations = 20_000;
        for (int i = 0; i < 2_000; i++) spin(i); // warm up JIT + the advice
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) spin(i);
        return (System.nanoTime() - start) / iterations;
    }

    static void spin(int n) {
        try {
            charge(n, 1.0);
        } catch (RuntimeException ignored) {
        }
    }

    private CoexistenceApp() {
    }
}
