package io.github.gabrielbbaldez.stacktale.jul;

import io.github.gabrielbbaldez.stacktale.ReportPipeline;
import io.github.gabrielbbaldez.stacktale.UncaughtHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class JulHandlerIntegrationTest {

    // a real report block starts at column 0; the file header only quotes the delimiter
    // mid-line, so anchor on the leading newline to tell them apart
    private static final String REPORT_START = "\n━━━ ERROR #";

    private StacktaleJulHandler attach(String loggerName, Path file, String... appPackages) {
        StacktaleJulHandler handler = new StacktaleJulHandler(ReportPipeline.Settings.builder()
                .file(file.toString()).appPackages(List.of(appPackages)).build(), false); // no global handler in these unit tests
        handler.setLevel(Level.ALL);
        Logger logger = Logger.getLogger(loggerName);
        logger.setUseParentHandlers(false); // isolate from console/root
        logger.setLevel(Level.ALL);
        for (var h : logger.getHandlers()) logger.removeHandler(h);
        logger.addHandler(handler);
        return handler;
    }

    @Test
    void severeRecordWithThrowableBecomesAReportWithTheStory(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        StacktaleJulHandler handler = attach("jultest.severe", file, "io.github.gabrielbbaldez");
        Logger log = Logger.getLogger("jultest.severe");

        log.info("loading order 889"); // feeds the story
        log.log(Level.SEVERE, "charge failed for the order",
                new IllegalStateException("payment gateway refused"));
        handler.close();

        String content = Files.readString(file);
        assertThat(content)
                .contains(REPORT_START)
                .contains("IllegalStateException: payment gateway refused")
                .contains("log: \"charge failed for the order\"")
                .contains("story")
                .contains("loading order 889");
    }

    @Test
    void julMessageParametersAreInlined(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        StacktaleJulHandler handler = attach("jultest.params", file);
        Logger log = Logger.getLogger("jultest.params");

        // JUL's own {0}/{1} MessageFormat style must be interpolated, not shown literally
        log.log(Level.SEVERE, "order {0} failed with code {1}", new Object[]{889, 502});
        handler.close();

        String content = Files.readString(file);
        assertThat(content).contains("order 889 failed with code 502").doesNotContain("{0}");
    }

    @Test
    void nonSevereRecordsFeedTheStoryButDoNotEmitAReport(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        StacktaleJulHandler handler = attach("jultest.info", file);
        Logger log = Logger.getLogger("jultest.info");

        log.info("everything is fine");
        log.warning("concerning but not an error");
        handler.close();

        // no error → no report (the file may not even be created, which is fine)
        String content = Files.exists(file) ? Files.readString(file) : "";
        assertThat(content).doesNotContain(REPORT_START);
    }

    @Test
    void installsTheUncaughtExceptionHandlerByDefault(@TempDir Path dir) throws Exception {
        // #55: the JUL adapter now installs UncaughtHandler like Logback/Log4j2, so a thread
        // that dies without a log.error() still produces a report.
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(null);
        try {
            StacktaleJulHandler handler = new StacktaleJulHandler(
                    ReportPipeline.Settings.builder().file(dir.resolve("errors-ai.log").toString()).build());
            assertThat(Thread.getDefaultUncaughtExceptionHandler()).isInstanceOf(UncaughtHandler.class);
            handler.close();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
    }

    @Test
    void canOptOutOfTheUncaughtExceptionHandler(@TempDir Path dir) throws Exception {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(null);
        try {
            StacktaleJulHandler handler = new StacktaleJulHandler(
                    ReportPipeline.Settings.builder().file(dir.resolve("errors-ai.log").toString()).build(), false);
            assertThat(Thread.getDefaultUncaughtExceptionHandler()).isNull();
            handler.close();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
    }
}
