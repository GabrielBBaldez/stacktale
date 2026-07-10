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
            ZoneId zone
    ) {}

    /** Callbacks into the hosting logging framework. */
    public interface Host {
        /** Emit one line through the framework's normal pipeline (logger {@code stacktale}). */
        void selfLog(String message);

        /** Report an internal stacktale problem through the framework's status/warn channel. */
        void warn(String message, Throwable t);
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

    public void process(LogEventData event) {
        try {
            if (writer == null || SELF_LOGGER.equals(event.loggerName())) return;
            if (announced.compareAndSet(false, true)) {
                host.selfLog("stacktale active → " + settings.file() + " (error reports for AI consumption)");
            }
            storyBuffer.record(event);
            if (!event.error()) return;

            Throwable throwable = event.throwable();
            if (throwable == null && !settings.reportErrorsWithoutThrowable()) return;

            DistilledStack stack = throwable == null ? null : distiller.distill(throwable);
            String fingerprint = stack != null
                    ? Fingerprinter.fingerprint(stack.rootType(), stack.culpritLine(), stack.rootMessage())
                    : Fingerprinter.fingerprint(event.loggerName(), "", event.messagePattern());

            Decision decision = deduper.decide(fingerprint);
            switch (decision.kind()) {
                case REPORT -> {
                    try {
                        Map<String, String> fields = settings.captureExceptionFields()
                                ? FieldExtractor.extractChain(throwable)
                                : Map.of();
                        Report report = new Report(fingerprint, event.epochMillis(), event.threadName(),
                                stack, event.messagePattern(), event.args(), event.loggerName(),
                                event.mdc(), fields, storyBuffer.storyFor(event), env.envLine());
                        writer.append(renderer.render(report));
                    } catch (Throwable t) {
                        // don't leave the dedup window believing a report exists that was
                        // never written — the next occurrence must get a fresh chance
                        deduper.rollback(fingerprint);
                        throw t;
                    }
                    host.selfLog("AI error report #" + fingerprint + " → " + settings.file());
                }
                case SUMMARY -> writer.append(
                        renderer.renderSummary(fingerprint, decision.count(), decision.lastSeenMillis()));
                case SILENT -> { /* counted; nothing to write */ }
            }
        } catch (Throwable t) {
            if (warnedOnce.compareAndSet(false, true)) {
                host.warn("stacktale failed to process an event; further failures are silent", t);
            }
        }
    }
}
