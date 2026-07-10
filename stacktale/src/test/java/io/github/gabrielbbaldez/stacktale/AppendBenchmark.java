package io.github.gabrielbbaldez.stacktale;
import io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Backs the "cheap happy path" claim with numbers. Not a CI test — run it manually:
 *
 * <pre>
 * mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 * java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" \
 *      io.github.gabrielbbaldez.stacktale.AppendBenchmark
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class AppendBenchmark {

    private Logger plainLogger;
    private Logger stacktaleLogger;
    private Logger errorLogger;
    private LoggerContext plainCtx;
    private LoggerContext stacktaleCtx;
    private RuntimeException recurringError;
    private int i;

    @Setup
    public void setUp() throws Exception {
        plainCtx = new LoggerContext();
        plainCtx.setMDCAdapter(org.slf4j.MDC.getMDCAdapter()); // production-like: adapter present
        plainCtx.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        plainLogger = plainCtx.getLogger("bench.Plain");

        stacktaleCtx = new LoggerContext();
        stacktaleCtx.setMDCAdapter(org.slf4j.MDC.getMDCAdapter());
        stacktaleCtx.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        Path file = Files.createTempDirectory("stacktale-bench").resolve("errors-ai.log");
        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(stacktaleCtx);
        appender.setFile(file.toString());
        appender.setInstallUncaughtHandler(false);
        appender.start();
        stacktaleCtx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        stacktaleLogger = stacktaleCtx.getLogger("bench.WithStacktale");
        errorLogger = stacktaleCtx.getLogger("bench.Errors");

        recurringError = new RuntimeException("recurring failure");
        errorLogger.error("prime the dedup so the loop measures the repeat path", recurringError);
    }

    @TearDown
    public void tearDown() {
        plainCtx.stop();
        stacktaleCtx.stop();
    }

    /** Logback with no appender attached — the floor we compare against. */
    @Benchmark
    public void infoBaselineNoAppenders() {
        plainLogger.info("processed item {} in step {}", i++, "checkout");
    }

    /** The stacktale happy path: same INFO event feeding the story ring buffer. */
    @Benchmark
    public void infoWithStacktale() {
        stacktaleLogger.info("processed item {} in step {}", i++, "checkout");
    }

    /** Repeated identical error: fingerprint + dedup decision, no report written. */
    @Benchmark
    public void errorRepeatedDeduped() {
        errorLogger.error("recurring failure on item {}", i++, recurringError);
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(new String[]{AppendBenchmark.class.getSimpleName()});
    }
}
