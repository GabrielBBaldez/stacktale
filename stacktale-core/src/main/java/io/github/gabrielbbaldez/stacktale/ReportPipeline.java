package io.github.gabrielbbaldez.stacktale;

import java.nio.file.Path;
import java.time.ZoneId;
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
            List<String> correlationMdcKeys,
            ZoneId zone,
            long echoSuppressionMillis,
            List<String> containerLoggers,
            boolean emitReportsToLogger
    ) {

        /** Default logger prefixes whose errors are re-logs of an exception the app already reported. */
        public static final List<String> DEFAULT_CONTAINER_LOGGERS =
                List.of("org.apache.catalina.core.ContainerBase");
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
    private final EnvCollector env;
    private final ReportRenderer renderer;
    private final ReportWriter writer; // null = broken config, pipeline is a no-op
    private final AtomicBoolean warnedOnce = new AtomicBoolean();
    private final AtomicBoolean announced = new AtomicBoolean();
    /** When this thread last produced a full report — used to suppress container echoes. */
    private final ThreadLocal<Long> lastReportOnThread = new ThreadLocal<>();

    private ReportPipeline(Settings settings, Host host, ReportWriter writer, ReportRenderer renderer) {
        this.settings = settings;
        this.host = host;
        this.renderer = renderer;
        this.writer = writer;
        this.storyBuffer = new StoryBuffer(settings.storySize(), settings.storyWindowMillis(),
                settings.correlationMdcKeys(), 200);
        this.distiller = new StackDistiller(settings.appPackages());
        this.deduper = new Deduper(settings.dedupWindowMillis(), 60_000, System::currentTimeMillis);
        this.env = new EnvCollector(Thread.currentThread().getContextClassLoader());
    }

    /** Never throws: a broken configuration produces a warned, no-op pipeline. */
    public static ReportPipeline create(Settings settings, Host host) {
        Redactor redactor = settings.redactionEnabled()
                ? Redactor.withDefaults(settings.redactPatterns())
                : Redactor.disabled();
        ReportRenderer renderer = new ReportRenderer(settings.zone(), redactor);
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
                    String rendered;
                    try {
                        Map<String, String> fields = settings.captureExceptionFields()
                                ? FieldExtractor.extractChain(throwable)
                                : Map.of();
                        Report report = new Report(fingerprint, event.epochMillis(), event.threadName(),
                                stack, event.messagePattern(), event.args(), event.loggerName(),
                                event.mdc(), fields, AgentCaptures.forChain(throwable),
                                storyBuffer.storyFor(event), env.envLine());
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
                    lastReportOnThread.set(System.currentTimeMillis());
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
        Long last = lastReportOnThread.get();
        return last != null && System.currentTimeMillis() - last <= settings.echoSuppressionMillis();
    }

    /** Flushes pending repeat counters (bursts silenced by the summary throttle) — call on shutdown. */
    public void close() {
        try {
            if (writer == null) return;
            for (Deduper.Pending pending : deduper.drainPending()) {
                writer.append(renderer.renderSummary(pending.fingerprint(), pending.count(), pending.lastSeenMillis()));
            }
        } catch (Throwable t) {
            // shutdown must never fail the host
        }
    }
}
