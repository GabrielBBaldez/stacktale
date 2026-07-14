package io.github.gabrielbbaldez.stacktale.jul;

import io.github.gabrielbbaldez.stacktale.Csv;
import io.github.gabrielbbaldez.stacktale.LogEventData;
import io.github.gabrielbbaldez.stacktale.ReportPipeline;
import io.github.gabrielbbaldez.stacktale.UncaughtHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The {@code java.util.logging} (JUL) face of stacktale — for apps that log through the
 * JDK's own logging, or through {@link System.Logger} (whose default provider routes to
 * JUL), rather than SLF4J/Logback or Log4j2. Attach it to a logger and {@code SEVERE}
 * records become {@code st/1} reports in a separate file while lower levels feed the story:
 *
 * <pre>{@code
 * Logger.getLogger("").addHandler(new StacktaleJulHandler(
 *         ReportPipeline.Settings.builder().appPackages(List.of("com.your.app")).build()));
 * }</pre>
 *
 * <p>Or declaratively in {@code logging.properties} (JUL instantiates the no-arg constructor
 * and this reads its own {@code <fqcn>.*} keys from the {@link LogManager}):
 *
 * <pre>
 * handlers = io.github.gabrielbbaldez.stacktale.jul.StacktaleJulHandler
 * io.github.gabrielbbaldez.stacktale.jul.StacktaleJulHandler.appPackages = com.your.app
 * io.github.gabrielbbaldez.stacktale.jul.StacktaleJulHandler.file = errors-ai.log
 * </pre>
 *
 * <p>JUL has no MDC, so the story correlates by thread. JUL {@code publish()} runs on the
 * logging thread, so that thread is recorded faithfully.
 */
public final class StacktaleJulHandler extends Handler {

    // formatMessage() applies JUL's ResourceBundle localization and {0}-style MessageFormat,
    // yielding the interpolated text — which we pass as the message (arguments already inlined,
    // so the report's log: line reads naturally instead of showing raw {0} placeholders).
    private static final Formatter FORMATTER = new Formatter() {
        @Override
        public String format(LogRecord record) {
            return formatMessage(record);
        }
    };

    private final ReportPipeline pipeline;

    /** Reads configuration from {@code logging.properties} — the JUL-native path. */
    public StacktaleJulHandler() {
        this(settingsFromLogManager(), installUncaughtHandlerFromLogManager());
        String level = LogManager.getLogManager().getProperty(getClass().getName() + ".level");
        if (level != null && !level.isBlank()) {
            try {
                setLevel(Level.parse(level.trim()));
            } catch (IllegalArgumentException ignored) {
                // keep the default level (ALL) on a bad value
            }
        }
    }

    public StacktaleJulHandler(ReportPipeline.Settings settings) {
        this(settings, true); // install the uncaught handler by default, like the other adapters
    }

    public StacktaleJulHandler(ReportPipeline.Settings settings, boolean installUncaughtHandler) {
        this.pipeline = ReportPipeline.create(settings, host());
        // The JVM sends uncaught exceptions to stderr, never through JUL — so without this a
        // thread that dies without a log.error() produces no report (#55). Route them back in
        // via UNCAUGHT_LOGGER, which propagates to this handler on the (parent) root logger.
        if (installUncaughtHandler && pipeline.isActive()) {
            UncaughtHandler.install((message, thrown) ->
                    Logger.getLogger(UncaughtHandler.UNCAUGHT_LOGGER).log(Level.SEVERE, message, thrown));
        }
    }

    private static boolean installUncaughtHandlerFromLogManager() {
        String v = LogManager.getLogManager().getProperty(
                StacktaleJulHandler.class.getName() + ".installUncaughtHandler");
        return v == null || Boolean.parseBoolean(v.trim());
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) return;
        try {
            pipeline.process(adapt(record));
        } catch (Throwable ignored) {
            // pipeline.process never throws; this only guards the adaptation itself
        }
    }

    private static LogEventData adapt(LogRecord record) {
        String message = FORMATTER.formatMessage(record);
        boolean error = record.getLevel().intValue() >= Level.SEVERE.intValue();
        String logger = record.getLoggerName() == null ? "" : record.getLoggerName();
        // arguments are already inlined into `message`, so pass null args (nothing to render)
        return new LogEventData(record.getMillis(), record.getLevel().getName(), error, logger,
                Thread.currentThread().getName(), message, null, message, Map.of(), record.getThrown());
    }

    @Override
    public void flush() {
        // reports are written synchronously in publish(); nothing is buffered here
    }

    @Override
    public void close() {
        pipeline.close(); // flush pending repeat counters / storm lines
    }

    private ReportPipeline.Host host() {
        return new ReportPipeline.Host() {
            @Override
            public void selfLog(String message) {
                Logger.getLogger(ReportPipeline.SELF_LOGGER).info(message);
            }

            @Override
            public void warn(String message, Throwable t) {
                Logger.getLogger(ReportPipeline.SELF_LOGGER).log(Level.WARNING, message, t);
            }

            @Override
            public void emitReport(String block) {
                Logger.getLogger(ReportPipeline.REPORTS_LOGGER).info(block);
            }
        };
    }

    private static ReportPipeline.Settings settingsFromLogManager() {
        LogManager m = LogManager.getLogManager();
        String p = StacktaleJulHandler.class.getName() + ".";
        ReportPipeline.Settings.Builder b = ReportPipeline.Settings.builder();

        String file = m.getProperty(p + "file");
        if (file != null && !file.isBlank()) b.file(file.trim());

        String appPackages = m.getProperty(p + "appPackages");
        if (appPackages != null) b.appPackages(Csv.parse(appPackages));

        String redaction = m.getProperty(p + "redactionEnabled");
        if (redaction != null) b.redactionEnabled(Boolean.parseBoolean(redaction.trim()));

        String correlation = m.getProperty(p + "redactionCorrelation");
        if (correlation != null) b.redactionCorrelation(Boolean.parseBoolean(correlation.trim()));

        // regexes may contain commas, so redactPatterns are separated by ';;' (as in Log4j2)
        String patterns = m.getProperty(p + "redactPatterns");
        if (patterns != null && !patterns.isBlank()) {
            List<Pattern> compiled = new ArrayList<>();
            for (String s : patterns.split(";;")) {
                if (s.isBlank()) continue;
                try {
                    compiled.add(Pattern.compile(s.trim()));
                } catch (RuntimeException ignored) {
                    // skip a bad pattern rather than fail handler construction
                }
            }
            b.redactPatterns(compiled);
        }

        b.maxReportsPerMinute(intProp(m, p + "maxReportsPerMinute", 0));
        b.storySize(intProp(m, p + "storySize", ReportPipeline.Settings.DEFAULT_STORY_SIZE));
        b.storyWindowMillis(intProp(m, p + "storyWindowSeconds",
                ReportPipeline.Settings.DEFAULT_STORY_WINDOW_SECONDS) * 1000L);
        b.dedupWindowMillis(intProp(m, p + "dedupWindowSeconds",
                ReportPipeline.Settings.DEFAULT_DEDUP_WINDOW_SECONDS) * 1000L);
        b.maxFileBytes(intProp(m, p + "maxFileSizeMb",
                ReportPipeline.Settings.DEFAULT_MAX_FILE_SIZE_MB) * 1024L * 1024L);
        b.maxBackups(intProp(m, p + "maxBackups", ReportPipeline.Settings.DEFAULT_MAX_BACKUPS));

        String format = m.getProperty(p + "format");
        if (format != null) b.jsonFormat("json".equalsIgnoreCase(format.trim()));
        return b.build();
    }

    private static int intProp(LogManager m, String key, int fallback) {
        String v = m.getProperty(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
