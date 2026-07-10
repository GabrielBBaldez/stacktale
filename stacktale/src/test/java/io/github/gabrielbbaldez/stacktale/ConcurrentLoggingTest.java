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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Many threads log errors at once: blocks must stay whole and dedup must hold. */
class ConcurrentLoggingTest {

    private LoggerContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.stop();
    }

    @Test
    void blocksStayIntactAndDedupHoldsUnderConcurrency(@TempDir Path dir) throws Exception {
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

        int threads = 8;
        int iterations = 25;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Thread> pool = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    Logger log = ctx.getLogger("com.acme.Worker" + (id % 4));
                    for (int i = 0; i < iterations; i++) {
                        log.info("working, iteration {}", i);
                        // 4 distinct error sites → 4 distinct fingerprints, each hit many times
                        switch (id % 4) {
                            case 0 -> log.error("w0 failed on {}", i, errorAt("com.acme.Worker0", 10));
                            case 1 -> log.error("w1 failed on {}", i, errorAt("com.acme.Worker1", 20));
                            case 2 -> log.error("w2 failed on {}", i, errorAt("com.acme.Worker2", 30));
                            default -> log.error("w3 failed on {}", i, errorAt("com.acme.Worker3", 40));
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "conc-" + t);
            pool.add(thread);
            thread.start();
        }
        start.countDown();
        done.await();

        String content = Files.readString(file, StandardCharsets.UTF_8);

        // exactly 4 full reports (one per distinct fingerprint), despite 200 error events
        long reports = content.lines().filter(l -> l.startsWith("━━━ ERROR #")).count();
        assertThat(reports).isEqualTo(4);

        // every block is whole: ERROR #id ... END #id in strict pairs, never interleaved
        Pattern delimiter = Pattern.compile("^━+ (ERROR|END) #([0-9a-f]{8})", Pattern.MULTILINE);
        Matcher m = delimiter.matcher(content);
        String openId = null;
        while (m.find()) {
            if (m.group(1).equals("ERROR")) {
                assertThat(openId).as("nested/interleaved report block").isNull();
                openId = m.group(2);
            } else {
                assertThat(m.group(2)).as("END id matches its ERROR id").isEqualTo(openId);
                openId = null;
            }
        }
        assertThat(openId).as("last block is closed").isNull();
    }

    private static RuntimeException errorAt(String cls, int line) {
        RuntimeException e = new RuntimeException("boom");
        e.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(cls, "work", "Worker.java", line)});
        return e;
    }
}
