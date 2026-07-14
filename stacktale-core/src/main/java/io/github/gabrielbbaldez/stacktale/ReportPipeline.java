package io.github.gabrielbbaldez.stacktale;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * The framework-agnostic heart of stacktale: feed it every log event (as
 * {@link LogEventData}) and it maintains the story, decides which errors deserve a
 * report, and writes st/ blocks to the report file. Logging-framework appenders are thin
 * adapters around this class.
 *
 * <p>Guarantees: {@link #process} never throws, and a pipeline whose configuration is
 * broken (e.g. an invalid file path) degrades to a no-op instead of failing the host.
 */
public final class ReportPipeline {

    /** Logger name used for stacktale's own announce/pointer lines (skipped by the pipeline). */
    public static final String SELF_LOGGER = "stacktale";

    /** Logger that carries whole report blocks to aggregators when {@code emitReportsToLogger} is on. */
    public static final String REPORTS_LOGGER = "stacktale.reports";

    /** All knobs, framework-neutral. Times in millis, sizes in bytes. */
    public record Settings(
            String file,
            List<String> appPackages,
            int storySize,
            long storyWindowMillis,
            long dedupWindowMillis,
            long maxFileBytes,
            int maxBackups,
            boolean truncateOnStart,
            boolean reportErrorsWithoutThrowable,
            boolean captureExceptionFields,
            boolean redactionEnabled,
            List<Pattern> redactPatterns,
            boolean redactionCorrelation,
            List<String> correlationMdcKeys,
            ZoneId zone,
            long echoSuppressionMillis,
            List<String> containerLoggers,
            boolean emitReportsToLogger,
            int maxReportsPerMinute,
            boolean jsonFormat
    ) {

        /** Default logger prefixes whose errors are re-logs of an exception the app already reported. */
        public static final List<String> DEFAULT_CONTAINER_LOGGERS =
                List.of("org.apache.catalina.core.ContainerBase");

        // Defaults, one source of truth — referenced by the Builder AND every framework
        // appender/property class so a change can't drift between backends. Human units
        // (seconds/MB) where the appenders expose them; the Builder converts to millis/bytes.
        public static final int DEFAULT_STORY_SIZE = 15;
        public static final int DEFAULT_STORY_WINDOW_SECONDS = 60;
        public static final int DEFAULT_DEDUP_WINDOW_SECONDS = 300;
        public static final int DEFAULT_MAX_FILE_SIZE_MB = 5;
        public static final int DEFAULT_MAX_BACKUPS = 1;
        public static final long DEFAULT_ECHO_SUPPRESSION_MILLIS = 2000;
        public static final String DEFAULT_CORRELATION_MDC_KEYS = "traceId,correlationId,requestId";

        public static Builder builder() {
            return new Builder();
        }

        /**
         * Fluent builder for {@link Settings}: the framework appenders assemble config
         * through named methods, so adding a knob never risks a positional-argument
         * mistake and every default lives in exactly one place. All times are millis,
         * sizes are bytes (framework-neutral).
         */
        public static final class Builder {
            private String file = "errors-ai.log";
            private List<String> appPackages = List.of();
            private int storySize = DEFAULT_STORY_SIZE;
            private long storyWindowMillis = DEFAULT_STORY_WINDOW_SECONDS * 1000L;
            private long dedupWindowMillis = DEFAULT_DEDUP_WINDOW_SECONDS * 1000L;
            private long maxFileBytes = DEFAULT_MAX_FILE_SIZE_MB * 1024L * 1024L;
            private int maxBackups = DEFAULT_MAX_BACKUPS;
            private boolean truncateOnStart = false;
            private boolean reportErrorsWithoutThrowable = true;
            private boolean captureExceptionFields = true;
            private boolean redactionEnabled = true;
            private List<Pattern> redactPatterns = List.of();
            private boolean redactionCorrelation = false;
            private List<String> correlationMdcKeys = Csv.parse(DEFAULT_CORRELATION_MDC_KEYS);
            private ZoneId zone = ZoneId.systemDefault();
            private long echoSuppressionMillis = DEFAULT_ECHO_SUPPRESSION_MILLIS;
            private List<String> containerLoggers = DEFAULT_CONTAINER_LOGGERS;
            private boolean emitReportsToLogger = false;
            private int maxReportsPerMinute = 0;
            private boolean jsonFormat = false;

            public Builder file(String v) { this.file = v; return this; }
            public Builder appPackages(List<String> v) { this.appPackages = v; return this; }
            public Builder storySize(int v) { this.storySize = v; return this; }
            public Builder storyWindowMillis(long v) { this.storyWindowMillis = v; return this; }
            public Builder dedupWindowMillis(long v) { this.dedupWindowMillis = v; return this; }
            public Builder maxFileBytes(long v) { this.maxFileBytes = v; return this; }
            public Builder maxBackups(int v) { this.maxBackups = v; return this; }
            public Builder truncateOnStart(boolean v) { this.truncateOnStart = v; return this; }
            public Builder reportErrorsWithoutThrowable(boolean v) { this.reportErrorsWithoutThrowable = v; return this; }
            public Builder captureExceptionFields(boolean v) { this.captureExceptionFields = v; return this; }
            public Builder redactionEnabled(boolean v) { this.redactionEnabled = v; return this; }
            public Builder redactPatterns(List<Pattern> v) { this.redactPatterns = v; return this; }
            public Builder redactionCorrelation(boolean v) { this.redactionCorrelation = v; return this; }
            public Builder correlationMdcKeys(List<String> v) { this.correlationMdcKeys = v; return this; }
            public Builder zone(ZoneId v) { this.zone = v; return this; }
            public Builder echoSuppressionMillis(long v) { this.echoSuppressionMillis = v; return this; }
            public Builder containerLoggers(List<String> v) { this.containerLoggers = v; return this; }
            public Builder emitReportsToLogger(boolean v) { this.emitReportsToLogger = v; return this; }
            public Builder maxReportsPerMinute(int v) { this.maxReportsPerMinute = v; return this; }
            public Builder jsonFormat(boolean v) { this.jsonFormat = v; return this; }

            public Settings build() {
                return new Settings(file, appPackages, storySize, storyWindowMillis, dedupWindowMillis,
                        maxFileBytes, maxBackups, truncateOnStart, reportErrorsWithoutThrowable,
                        captureExceptionFields, redactionEnabled, redactPatterns, redactionCorrelation,
                        correlationMdcKeys, zone,
                        echoSuppressionMillis, containerLoggers, emitReportsToLogger, maxReportsPerMinute,
                        jsonFormat);
            }
        }
    }

    /** Callbacks into the hosting logging framework. */
    public interface Host {
        /** Emit one line through the framework's normal pipeline (logger {@code stacktale}). */
        void selfLog(String message);

        /** Report an internal stacktale problem through the framework's status/warn channel. */
        void warn(String message, Throwable t);

        /**
         * Carry a whole report block as ONE log event through logger
         * {@link #REPORTS_LOGGER} — existing shippers (Loki, ELK, CloudWatch agents)
         * pick it up with zero stacktale-specific infrastructure.
         */
        default void emitReport(String block) {}
    }

    private final Settings settings;
    private final Host host;
    private final StoryBuffer storyBuffer;
    private final StackDistiller distiller;
    private final Deduper deduper;
    private final StormLimiter stormLimiter;
    private final EnvCollector env;
    private final Renderer renderer;
    private final ReportWriter writer; // null = broken config, pipeline is a no-op
    private final AtomicBoolean warnedOnce = new AtomicBoolean();
    private final AtomicBoolean announced = new AtomicBoolean();
    /**
     * When a logical thread last produced a full report — used to suppress container echoes.
     * Keyed by the event's thread NAME (not the physical thread) so it stays correct under
     * Logback AsyncAppender, where every event is processed on one worker thread. Bounded LRU.
     */
    private final Map<String, Long> lastReportByThread =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> e) {
                    return size() > 256;
                }
            };

    private ReportPipeline(Settings settings, Host host, ReportWriter writer, Renderer renderer) {
        this.settings = settings;
        this.host = host;
        this.renderer = renderer;
        this.writer = writer;
        this.storyBuffer = new StoryBuffer(settings.storySize(), settings.storyWindowMillis(),
                settings.correlationMdcKeys(), 200);
        this.distiller = new StackDistiller(settings.appPackages());
        this.deduper = new Deduper(settings.dedupWindowMillis(), 60_000, System::currentTimeMillis);
        this.stormLimiter = settings.maxReportsPerMinute() > 0
                ? new StormLimiter(settings.maxReportsPerMinute(), 60_000, 10_000, System::currentTimeMillis)
                : StormLimiter.disabled();
        this.env = new EnvCollector(Thread.currentThread().getContextClassLoader());
    }

    /** Never throws: a broken configuration produces a warned, no-op pipeline. */
    public static ReportPipeline create(Settings settings, Host host) {
        Redactor redactor = settings.redactionEnabled()
                ? Redactor.withDefaults(settings.redactPatterns(), settings.redactionCorrelation())
                : Redactor.disabled();
        Renderer renderer = settings.jsonFormat()
                ? new JsonReportRenderer(settings.zone(), redactor)
                : new ReportRenderer(settings.zone(), redactor);
        ReportWriter writer;
        try {
            String marker = renderer.sessionMarker(System.currentTimeMillis(), ProcessHandle.current().pid());
            writer = new ReportWriter(Path.of(settings.file()), settings.maxFileBytes(), renderer.fileHeader(),
                    marker, settings.truncateOnStart(), settings.maxBackups());
        } catch (RuntimeException e) {
            host.warn("invalid report file '" + settings.file() + "', stacktale disabled", e);
            writer = null;
        }
        return new ReportPipeline(settings, host, writer, renderer);
    }

    /** False when configuration was broken at creation time and the pipeline is a no-op. */
    public boolean isActive() {
        return writer != null;
    }

    public void process(LogEventData event) {
        try {
            if (writer == null || SELF_LOGGER.equals(event.loggerName())
                    || REPORTS_LOGGER.equals(event.loggerName())) return;
            if (announced.compareAndSet(false, true)) {
                host.selfLog("stacktale active → " + settings.file() + " (error reports for AI consumption)");
            }
            storyBuffer.record(event);
            if (!event.error()) return;

            // container echo: Tomcat/Spring re-log the exception the app just reported —
            // suppress the duplicate ONLY when this thread produced a report moments ago,
            // so apps that don't log before rethrowing still get their container report
            if (isContainerEcho(event)) return;

            Throwable throwable = event.throwable();
            if (throwable == null && !settings.reportErrorsWithoutThrowable()) return;

            DistilledStack stack = throwable == null ? null : distiller.distill(throwable);
            String fingerprint = stack != null
                    ? Fingerprinter.fingerprint(stack.rootType(), stack.culpritLine(), stack.rootMessage())
                    : Fingerprinter.fingerprint(event.loggerName(), "", event.messagePattern());

            Decision decision = deduper.decide(fingerprint);
            switch (decision.kind()) {
                case REPORT -> {
                    // storm control gates only full reports (summaries are already throttled);
                    // beyond the rate limit, distinct errors are counted, not dumped
                    StormLimiter.Outcome storm = stormLimiter.onReport();
                    if (storm.action() == StormLimiter.Action.SUPPRESS) {
                        deduper.rollback(fingerprint); // #51: report not written — re-arm a fresh one
                        return;
                    }
                    if (storm.action() == StormLimiter.Action.STORM_LINE) {
                        writer.append(renderer.stormLine(storm.suppressed(), stormLimiter.maxPerWindow()));
                        deduper.rollback(fingerprint); // #51: this error's own report wasn't written
                        return;
                    }
                    String rendered;
                    try {
                        Map<String, String> fields = settings.captureExceptionFields()
                                ? FieldExtractor.extractChain(throwable)
                                : Map.of();
                        Report report = new Report(fingerprint, event.epochMillis(), event.threadName(),
                                stack, event.messagePattern(), event.args(), event.loggerName(),
                                event.mdc(), fields, AgentCaptures.forChain(throwable),
                                storyBuffer.storyFor(event), env.envLine(),
                                decision.totalOccurrences(), decision.firstSeenMillis());
                        rendered = renderer.render(report);
                        writer.append(rendered);
                    } catch (Throwable t) {
                        // the report was NOT durably written — don't leave the dedup window
                        // believing it exists; the next occurrence must get a fresh chance
                        deduper.rollback(fingerprint);
                        throw t;
                    }
                    // past this point the report is on disk: a failing shipper must not
                    // undo dedup state (that would duplicate the next occurrence's report)
                    deduper.confirmReport(fingerprint); // #51: clears the retry flag now it's written
                    synchronized (lastReportByThread) {
                        lastReportByThread.put(threadKey(event), System.currentTimeMillis());
                    }
                    host.selfLog("AI error report #" + fingerprint + " → " + settings.file());
                    if (settings.emitReportsToLogger()) host.emitReport(rendered);
                }
                case SUMMARY -> {
                    writer.append(renderer.renderSummary(fingerprint, decision.count(), decision.lastSeenMillis()));
                    // only now is the count durably on file; a failed append above throws
                    // to the outer catch and leaves it pending for close()'s drainPending()
                    deduper.confirmWritten(fingerprint, decision.count());
                }
                case SILENT -> { /* counted; nothing to write */ }
            }
        } catch (Throwable t) {
            if (warnedOnce.compareAndSet(false, true)) {
                host.warn("stacktale failed to process an event; further failures are silent", t);
            }
        }
    }

    private boolean isContainerEcho(LogEventData event) {
        if (settings.echoSuppressionMillis() <= 0) return false;
        String logger = event.loggerName();
        boolean container = false;
        for (String prefix : settings.containerLoggers()) {
            if (logger.startsWith(prefix)) {
                container = true;
                break;
            }
        }
        if (!container) return false;
        Long last;
        synchronized (lastReportByThread) {
            last = lastReportByThread.get(threadKey(event));
        }
        return last != null && System.currentTimeMillis() - last <= settings.echoSuppressionMillis();
    }

    /** Logical thread name, with a stable fallback for unnamed (e.g. virtual) threads. */
    private static String threadKey(LogEventData event) {
        String t = event.threadName();
        return (t == null || t.isBlank()) ? "<unnamed>" : t;
    }

    /** Flushes pending repeat counters and storm-suppressed reports — call on shutdown. */
    public void close() {
        try {
            if (writer == null) return;
            for (Deduper.Pending pending : deduper.drainPending()) {
                writer.append(renderer.renderSummary(pending.fingerprint(), pending.count(), pending.lastSeenMillis()));
            }
            int suppressed = stormLimiter.drainSuppressed();
            if (suppressed > 0) {
                writer.append(renderer.stormLine(suppressed, stormLimiter.maxPerWindow()));
            }
        } catch (Throwable t) {
            // shutdown must never fail the host
        }
    }
}
