package io.github.gabrielbbaldez.stacktale;
import io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StacktaleAppenderIntegrationTest {

    private LoggerContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.stop();
    }

    private StacktaleAppender startAppender(Path file, String appPackages) {
        ctx = new LoggerContext();
        // hand-built contexts have no MDCAdapter; wire the global one so MDC.put reaches events
        ctx.setMDCAdapter(MDC.getMDCAdapter());
        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setFile(file.toString());
        appender.setAppPackages(appPackages);
        appender.setInstallUncaughtHandler(false);
        appender.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        return appender;
    }

    @Test
    void jsonFormatWritesNdjsonInsteadOfTheTextBlock(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ctx = new LoggerContext();
        ctx.setMDCAdapter(MDC.getMDCAdapter());
        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setFile(file.toString());
        appender.setAppPackages("io.github.gabrielbbaldez");
        appender.setInstallUncaughtHandler(false);
        appender.setFormat("json"); // must be set before start() — the pipeline is built there
        appender.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);

        ctx.getLogger("com.acme.Svc").error("charge failed", new IllegalStateException("gateway refused"));
        appender.stop();

        var lines = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(s -> !s.isBlank()).toList();
        assertThat(lines).isNotEmpty();
        // every entry is a standalone JSON object (NDJSON), header and session included
        assertThat(lines).allSatisfy(line ->
                assertThat(line.strip()).startsWith("{").endsWith("}"));
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .contains("\"type\":\"report\"")
                .contains("\"error\":{\"type\":\"IllegalStateException\"")
                .contains("\"message\":\"gateway refused\""));
        // genuinely JSON — the text delimiter is nowhere in the file
        assertThat(Files.readString(file)).doesNotContain("━━━ ERROR #");
    }

    private Exception wrappedNpe() {
        try {
            try {
                String s = null;
                s.length(); // real NPE with real frames
            } catch (NullPointerException npe) {
                throw new IllegalStateException("confirm failed", npe);
            }
        } catch (Exception e) {
            return e;
        }
        throw new AssertionError("unreachable");
    }

    @Test
    void fullPipelineWritesReportWithStoryAndDedups(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        startAppender(file, "io.github.gabrielbbaldez");
        Logger app = ctx.getLogger("com.acme.OrderService");

        MDC.put("traceId", "9f3a");
        try {
            app.info("POST /orders/123/confirm");
            app.warn("customer cache miss for 555, returning null");
            Exception e = wrappedNpe();
            for (int i = 0; i < 5; i++) app.error("Failed to confirm order {}", 123, e);
        } finally {
            MDC.clear();
        }

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("format st/1");                       // self-describing header
        // reports start the line with the delimiter (the file header only quotes it mid-line)
        assertThat(content).containsOnlyOnce("\n━━━ ERROR #");             // 5 identical errors → 1 report
        assertThat(content).contains("NullPointerException");
        assertThat(content).contains("wrapped by: IllegalStateException"); // root cause first
        assertThat(content).contains("POST /orders/123/confirm");          // story present
        assertThat(content).contains("← this error");
        assertThat(content).contains("traceId=9f3a");
        assertThat(content).contains("repeated");                          // dedup summary line
        assertThat(content).contains("━━━ END #");
    }

    @Test
    void errorWithoutThrowableStillReports(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        startAppender(file, "");
        ctx.getLogger("com.acme.Pay").error("payment rejected for order {}", 77);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("ERROR (no exception): payment rejected for order 77");
        // "stack (distilled," with a comma is the real section; the file header says "distilled;"
        assertThat(content).doesNotContain("stack (distilled,");
    }

    @Test
    void neverThrowsOutOfAppendEvenWhenBroken(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sub").resolve("errors-ai.log");
        StacktaleAppender appender = startAppender(file, "");
        // sabotage: make the report file path a directory so writes explode
        Files.createDirectories(file);
        Logger app = ctx.getLogger("com.acme.X");
        app.error("boom", new RuntimeException("x")); // must not propagate
        app.info("still alive");
        assertThat(appender.isStarted()).isTrue();
    }

    static class GatewayException extends RuntimeException {
        GatewayException(String msg) { super(msg); }
        public int getStatusCode() { return 502; }
        public boolean isRetryable() { return true; }
    }

    @Test
    void exceptionFieldsAppearInReport(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        startAppender(file, "");
        ctx.getLogger("com.acme.Gw").error("gateway call failed", new GatewayException("bad gateway"));
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("fields: retryable=true statusCode=502");
    }

    @Test
    void emitsReportBlocksThroughTheReportsLoggerWhenEnabled(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> shipper =
                new ch.qos.logback.core.read.ListAppender<>();
        shipper.start();

        ctx = new LoggerContext();
        ctx.setMDCAdapter(MDC.getMDCAdapter());
        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setFile(file.toString());
        appender.setInstallUncaughtHandler(false);
        appender.setEmitReportsToLogger(true);
        appender.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        // an aggregator shipper would be attached exactly like this
        ctx.getLogger("stacktale.reports").addAppender(shipper);

        ctx.getLogger("com.acme.X").info("noise before");
        ctx.getLogger("com.acme.X").error("boom", new RuntimeException("x"));

        assertThat(shipper.list)
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                        .contains("━━━ ERROR #").contains("━━━ END #")); // whole block, ONE event
        // and the reports logger must not pollute the story of later errors
        ctx.getLogger("com.acme.Y").error("second failure", new IllegalStateException("y"));
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).doesNotContain("stacktale.reports");
    }

    @Test
    void stormControlBoundsAFloodOfDistinctErrors(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ctx = new LoggerContext();
        ctx.setMDCAdapter(MDC.getMDCAdapter());
        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setFile(file.toString());
        appender.setInstallUncaughtHandler(false);
        appender.setMaxReportsPerMinute(5); // cascade cap
        appender.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);

        Logger app = ctx.getLogger("com.acme.Cascade");
        // 200 DISTINCT failures (different messages+sites) — a real dependency collapse
        for (int i = 0; i < 200; i++) {
            RuntimeException e = new RuntimeException("downstream " + i + " unavailable");
            e.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("com.acme.Cascade", "call" + i, "Cascade.java", i + 1)});
            app.error("call to service {} failed", i, e);
        }
        appender.stop();

        String content = Files.readString(file, StandardCharsets.UTF_8);
        long fullReports = content.lines().filter(l -> l.startsWith("━━━ ERROR #")).count();
        assertThat(fullReports).isLessThanOrEqualTo(5);      // bounded, not 200
        assertThat(content).contains("storm:");              // the flood is acknowledged, not lost
    }

    @Test
    void suppressesContainerEchoRightAfterAppReport(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        startAppender(file, "");
        Exception e = wrappedNpe();
        ctx.getLogger("com.acme.OrderService").error("checkout failed", e);
        // Tomcat re-logs the failure moments later on the same thread — must NOT double-report
        ctx.getLogger("org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]")
                .error("Servlet.service() threw exception", new IllegalStateException("rethrown"));
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).containsOnlyOnce("\n━━━ ERROR #");
    }

    @Test
    void containerErrorAloneStillGetsItsReport(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        startAppender(file, "");
        // the app never logged: the container report is the only signal — keep it
        ctx.getLogger("org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]")
                .error("Servlet.service() threw exception", new IllegalStateException("unlogged failure"));
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("IllegalStateException: unlogged failure");
    }

    @Test
    void flushesPendingRepeatCountersOnStop(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        StacktaleAppender appender = startAppender(file, "");
        Exception e = wrappedNpe();
        for (int i = 0; i < 6; i++) ctx.getLogger("com.acme.Burst").error("burst failure", e);
        appender.stop();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("repeated 6×"); // the file must not understate the burst
    }

    @Test
    void redactsSecretsInEveryReportSection(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        startAppender(file, "");
        MDC.put("session", "token=abc123xyz789secret");
        try {
            ctx.getLogger("com.acme.Auth").info("user gabriel@example.com logging in");
            ctx.getLogger("com.acme.Auth").error("auth failed password=hunter2 for {}", "gabriel@example.com",
                    new IllegalStateException("Bearer abcdef1234567890TOKENVALUE rejected"));
        } finally {
            MDC.clear();
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content)
                .doesNotContain("hunter2")               // message pattern
                .doesNotContain("gabriel@example.com")   // args + story
                .doesNotContain("TOKENVALUE")            // exception message
                .doesNotContain("abc123xyz789secret")    // mdc value
                .contains("███");
    }

    @Test
    void invalidFilePathDegradesToNoOpInsteadOfBreakingStartup(@TempDir Path dir) {
        ctx = new LoggerContext();
        ctx.setMDCAdapter(MDC.getMDCAdapter());
        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setFile("bad\0path.log"); // NUL is invalid on every filesystem
        appender.setInstallUncaughtHandler(false);
        appender.start(); // must not throw — a broken config cannot break app startup
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);

        ctx.getLogger("com.acme.X").error("boom", new RuntimeException("x")); // no-op, no throw
        assertThat(appender.isStarted()).isTrue();
    }

    @Test
    void ignoresItsOwnPointerLogger(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        startAppender(file, "");
        ctx.getLogger("stacktale").error("this must not produce a report", new RuntimeException("x"));
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void announcesOnStartAndEmitsPointerLineOnReport(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> console =
                new ch.qos.logback.core.read.ListAppender<>();
        console.start();

        ctx = new LoggerContext();
        ctx.setMDCAdapter(MDC.getMDCAdapter());
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(console);
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setFile(file.toString());
        appender.setInstallUncaughtHandler(false);
        appender.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);

        ctx.getLogger("com.acme.X").error("boom", new RuntimeException("x"));

        assertThat(console.list)
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("stacktale active"))
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("AI error report #"));
        assertThat(console.list)
                .allSatisfy(e -> assertThat(e.getLoggerName()).satisfiesAnyOf(
                        n -> assertThat(n).isEqualTo("stacktale"),
                        n -> assertThat(n).isEqualTo("com.acme.X")));
    }

    @Test
    void uncaughtExceptionsFlowThroughPipeline(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        startAppender(file, "");
        org.slf4j.Logger uncaughtLogger = ctx.getLogger(UncaughtHandler.UNCAUGHT_LOGGER);
        UncaughtHandler handler = new UncaughtHandler(null, uncaughtLogger::error);
        Thread t = new Thread(() -> {
            throw new IllegalArgumentException("thread died");
        }, "worker-1");
        t.setUncaughtExceptionHandler(handler);
        t.start();
        t.join();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("Uncaught exception in thread worker-1").contains("IllegalArgumentException");
    }
}
