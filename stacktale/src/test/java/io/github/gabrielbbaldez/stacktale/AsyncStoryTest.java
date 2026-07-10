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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** The story must survive thread hops when MDC is propagated — and the wrappers do that. */
class AsyncStoryTest {

    private LoggerContext ctx;
    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.stop();
        if (pool != null) pool.shutdownNow();
        MDC.clear();
    }

    private Path start(Path dir) {
        Path file = dir.resolve("errors-ai.log");
        ctx = new LoggerContext();
        ctx.setMDCAdapter(MDC.getMDCAdapter());
        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setFile(file.toString());
        appender.setInstallUncaughtHandler(false);
        appender.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        return file;
    }

    @Test
    void wrappedHopKeepsTheStoryTogether(@TempDir Path dir) throws Exception {
        Path file = start(dir);
        pool = Executors.newFixedThreadPool(1);
        Logger log = ctx.getLogger("com.acme.Checkout");

        MDC.put("traceId", "hop1");
        log.info("charging card before the hop");
        CompletableFuture.runAsync(StacktaleExecutors.wrap(() ->
                log.error("charge failed after hop", new IllegalStateException("declined"))), pool).join();

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("charging card before the hop");   // pre-hop event in the story
        assertThat(content).contains("traceId=hop1");
    }

    @Test
    void unwrappedHopLosesTheStory(@TempDir Path dir) throws Exception {
        Path file = start(dir);
        pool = Executors.newFixedThreadPool(1);
        Logger log = ctx.getLogger("com.acme.Checkout");

        MDC.put("traceId", "hop2");
        log.info("charging card before the hop");
        CompletableFuture.runAsync(() ->
                log.error("charge failed after hop", new IllegalStateException("declined")), pool).join();

        String content = Files.readString(file, StandardCharsets.UTF_8);
        // documented limitation: without MDC propagation the story fragments
        assertThat(content).doesNotContain("charging card before the hop");
    }
}
