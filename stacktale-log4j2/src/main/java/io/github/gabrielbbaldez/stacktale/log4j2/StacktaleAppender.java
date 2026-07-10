package io.github.gabrielbbaldez.stacktale.log4j2;

import io.github.gabrielbbaldez.stacktale.LogEventData;
import io.github.gabrielbbaldez.stacktale.ReportPipeline;
import io.github.gabrielbbaldez.stacktale.UncaughtHandler;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.status.StatusLogger;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Log4j2 face of stacktale: adapts {@link LogEvent}s into the framework-agnostic
 * {@link ReportPipeline}, which writes AI-oriented error reports to a separate file
 * while the human log stays untouched.
 *
 * <pre>{@code
 * <Configuration packages="io.github.gabrielbbaldez.stacktale.log4j2">
 *   <Appenders>
 *     <Stacktale name="STACKTALE" appPackages="com.your.app"/>
 *   </Appenders>
 *   <Loggers>
 *     <Root level="info"><AppenderRef ref="STACKTALE"/></Root>
 *   </Loggers>
 * </Configuration>
 * }</pre>
 */
@Plugin(name = "Stacktale", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public final class StacktaleAppender extends AbstractAppender {

    private final ReportPipeline pipeline;
    private final boolean installUncaughtHandler;

    private StacktaleAppender(String name, ReportPipeline pipeline, boolean installUncaughtHandler) {
        super(name, null, null, true, Property.EMPTY_ARRAY);
        this.pipeline = pipeline;
        this.installUncaughtHandler = installUncaughtHandler;
    }

    @Override
    public void start() {
        super.start();
        if (installUncaughtHandler) {
            org.apache.logging.log4j.Logger uncaught = LogManager.getLogger(UncaughtHandler.UNCAUGHT_LOGGER);
            UncaughtHandler.install(uncaught::error);
        }
    }

    @Override
    public void append(LogEvent event) {
        try {
            pipeline.process(adapt(event));
        } catch (Throwable t) {
            // pipeline.process never throws; this guards the adaptation itself
        }
    }

    private LogEventData adapt(LogEvent event) {
        org.apache.logging.log4j.message.Message message = event.getMessage();
        Map<String, String> mdc = event.getContextData() == null ? Map.of() : event.getContextData().toMap();
        Throwable thrown = event.getThrown();
        Object[] args = message.getParameters();
        // unlike SLF4J, Log4j2 keeps the trailing throwable inside getParameters() even
        // after extracting it as getThrown() — drop it so args= shows only real values
        if (thrown != null && args != null && args.length > 0 && args[args.length - 1] == thrown) {
            args = java.util.Arrays.copyOf(args, args.length - 1);
        }
        return new LogEventData(
                event.getInstant().getEpochMillisecond(),
                event.getLevel().name(),
                event.getLevel().isMoreSpecificThan(Level.ERROR),
                event.getLoggerName(),
                event.getThreadName(),
                message.getFormat(),
                args,
                message.getFormattedMessage(),
                mdc,
                thrown);
    }

    @PluginBuilderFactory
    public static Builder newBuilder() {
        return new Builder();
    }

    /** All attributes optional; names mirror the Logback appender's properties. */
    public static final class Builder implements org.apache.logging.log4j.core.util.Builder<StacktaleAppender> {

        @PluginBuilderAttribute private String name = "STACKTALE";
        @PluginBuilderAttribute private String file = "errors-ai.log";
        @PluginBuilderAttribute private String appPackages = "";
        @PluginBuilderAttribute private int storySize = 15;
        @PluginBuilderAttribute private int storyWindowSeconds = 60;
        @PluginBuilderAttribute private int dedupWindowSeconds = 300;
        @PluginBuilderAttribute private int maxFileSizeMb = 5;
        @PluginBuilderAttribute private int maxBackups = 1;
        @PluginBuilderAttribute private boolean truncateOnStart = false;
        @PluginBuilderAttribute private boolean installUncaughtHandler = true;
        @PluginBuilderAttribute private boolean reportErrorsWithoutThrowable = true;
        @PluginBuilderAttribute private boolean captureExceptionFields = true;
        @PluginBuilderAttribute private boolean redactionEnabled = true;
        /** Extra redaction regexes, separated by {@code ;;} (regexes may contain commas). */
        @PluginBuilderAttribute private String redactPatterns = "";
        @PluginBuilderAttribute private String correlationMdcKeys = "traceId,correlationId,requestId";
        @PluginBuilderAttribute private String zone = "";

        @Override
        public StacktaleAppender build() {
            ZoneId zoneId;
            try {
                zoneId = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
            } catch (DateTimeException e) {
                StatusLogger.getLogger().warn("stacktale: invalid zone '{}', using system default", zone);
                zoneId = ZoneId.systemDefault();
            }
            List<Pattern> compiled = new java.util.ArrayList<>();
            if (redactPatterns != null && !redactPatterns.isBlank()) {
                for (String p : redactPatterns.split(";;")) {
                    if (p.isBlank()) continue;
                    try {
                        compiled.add(Pattern.compile(p.trim()));
                    } catch (RuntimeException e) {
                        StatusLogger.getLogger().warn("stacktale: invalid redactPattern '{}' ignored", p);
                    }
                }
            }
            ReportPipeline.Settings settings = new ReportPipeline.Settings(
                    file, csv(appPackages), storySize, storyWindowSeconds * 1000L,
                    dedupWindowSeconds * 1000L, maxFileSizeMb * 1024L * 1024L, maxBackups, truncateOnStart,
                    reportErrorsWithoutThrowable, captureExceptionFields, redactionEnabled, compiled,
                    csv(correlationMdcKeys), zoneId);
            ReportPipeline pipeline = ReportPipeline.create(settings, new ReportPipeline.Host() {
                @Override
                public void selfLog(String message) {
                    LogManager.getLogger(ReportPipeline.SELF_LOGGER).info(message);
                }

                @Override
                public void warn(String message, Throwable t) {
                    StatusLogger.getLogger().warn("stacktale: {}", message, t);
                }
            });
            return new StacktaleAppender(name, pipeline, installUncaughtHandler);
        }

        private static List<String> csv(String s) {
            return s == null || s.isBlank() ? List.of()
                    : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        }

        public Builder setName(String name) { this.name = name; return this; }
        public Builder setFile(String file) { this.file = file; return this; }
        public Builder setAppPackages(String appPackages) { this.appPackages = appPackages; return this; }
    }
}
