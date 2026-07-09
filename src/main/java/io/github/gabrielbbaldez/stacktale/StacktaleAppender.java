package io.github.gabrielbbaldez.stacktale;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The stacktale entry point: a Logback appender that watches the event stream, keeps a
 * short story of what each context has been doing, and — when an ERROR passes by — writes
 * a complete, AI-oriented error report to a separate file. The human log stays untouched;
 * a single pointer line (logger {@code stacktale}) links console and report.
 *
 * <p>Guarantee: this appender never throws out of {@link #append} and never blocks the
 * happy path with I/O. If something inside stacktale breaks, it degrades to a no-op.
 */
public final class StacktaleAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    /** Logger used for announce/pointer lines; events from it are ignored by the pipeline. */
    private static final String SELF_LOGGER = "stacktale";

    private String file = "errors-ai.log";
    private String appPackages = "";
    private int storySize = 15;
    private int storyWindowSeconds = 60;
    private int dedupWindowSeconds = 300;
    private int maxFileSizeMb = 5;
    private boolean installUncaughtHandler = true;
    private boolean reportErrorsWithoutThrowable = true;
    private String correlationMdcKeys = "traceId,correlationId,requestId";
    private String zone = "";

    private StoryBuffer storyBuffer;
    private StackDistiller distiller;
    private Deduper deduper;
    private EnvCollector env;
    private ReportRenderer renderer;
    private ReportWriter writer;
    private org.slf4j.Logger selfLogger;
    private final AtomicBoolean warnedOnce = new AtomicBoolean();
    private final AtomicBoolean announced = new AtomicBoolean();

    @Override
    public void start() {
        ZoneId zoneId;
        try {
            zoneId = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
        } catch (DateTimeException e) {
            addWarn("invalid zone '" + zone + "', falling back to system default", e);
            zoneId = ZoneId.systemDefault();
        }
        storyBuffer = new StoryBuffer(storySize, storyWindowSeconds * 1000L, csv(correlationMdcKeys), 200);
        distiller = new StackDistiller(csv(appPackages));
        deduper = new Deduper(dedupWindowSeconds * 1000L, 60_000, System::currentTimeMillis);
        env = new EnvCollector(Thread.currentThread().getContextClassLoader());
        renderer = new ReportRenderer(zoneId);
        try {
            writer = new ReportWriter(Path.of(file), maxFileSizeMb * 1024L * 1024L, renderer.fileHeader());
        } catch (RuntimeException e) {
            // e.g. InvalidPathException from a value the OS rejects: a broken stacktale
            // config must degrade to a no-op, never break application startup
            addWarn("invalid <file> '" + file + "', stacktale disabled", e);
            writer = null;
        }
        // Log through this appender's own context: during application boot start() runs in
        // the middle of SLF4J initialization, and the global LoggerFactory is not safe yet
        // (and would be the wrong context in multi-context environments).
        selfLogger = getContext() instanceof ch.qos.logback.classic.LoggerContext lc
                ? lc.getLogger(SELF_LOGGER)
                : LoggerFactory.getLogger(SELF_LOGGER);
        super.start();
        // Status message always works; the logger announcement is deferred to the first
        // event because during Joran config no appender-ref is attached to any logger yet.
        addInfo("stacktale active → " + file + " (error reports for AI consumption)");
        if (installUncaughtHandler) UncaughtHandler.install();
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            if (writer == null || SELF_LOGGER.equals(event.getLoggerName())) return;
            if (announced.compareAndSet(false, true)) {
                // deferred from start(): by now appender-refs are wired, so this reaches the console
                selfLogger.info("stacktale active → {} (error reports for AI consumption)", file);
            }
            storyBuffer.record(event);
            if (!event.getLevel().isGreaterOrEqual(Level.ERROR)) return;

            IThrowableProxy proxy = event.getThrowableProxy();
            if (proxy == null && !reportErrorsWithoutThrowable) return;

            DistilledStack stack = proxy == null ? null : distiller.distill(proxy);
            String fingerprint = stack != null
                    ? Fingerprinter.fingerprint(stack.rootType(), stack.culpritLine(), stack.rootMessage())
                    : Fingerprinter.fingerprint(event.getLoggerName(), "", event.getMessage());

            Decision decision = deduper.decide(fingerprint);
            switch (decision.kind()) {
                case REPORT -> {
                    try {
                        Report report = new Report(fingerprint, event.getTimeStamp(), event.getThreadName(),
                                stack, event.getMessage(), event.getArgumentArray(), event.getLoggerName(),
                                StoryBuffer.safeMdc(event), storyBuffer.storyFor(event), env.envLine());
                        writer.append(renderer.render(report));
                    } catch (Throwable t) {
                        // don't leave the dedup window believing a report exists that was
                        // never written — the next occurrence must get a fresh chance
                        deduper.rollback(fingerprint);
                        throw t;
                    }
                    selfLogger.info("AI error report #{} → {}", fingerprint, file);
                }
                case SUMMARY -> writer.append(
                        renderer.renderSummary(fingerprint, decision.count(), decision.lastSeenMillis()));
                case SILENT -> { /* counted; nothing to write */ }
            }
        } catch (Throwable t) {
            if (warnedOnce.compareAndSet(false, true)) {
                addWarn("stacktale failed to process an event; further failures are silent", t);
            }
        }
    }

    private static List<String> csv(String s) {
        return s == null || s.isBlank() ? List.of()
                : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    // --- Logback config setters ---

    public void setFile(String file) { this.file = file; }

    public void setAppPackages(String appPackages) { this.appPackages = appPackages; }

    public void setStorySize(int storySize) { this.storySize = storySize; }

    public void setStoryWindowSeconds(int storyWindowSeconds) { this.storyWindowSeconds = storyWindowSeconds; }

    public void setDedupWindowSeconds(int dedupWindowSeconds) { this.dedupWindowSeconds = dedupWindowSeconds; }

    public void setMaxFileSizeMb(int maxFileSizeMb) { this.maxFileSizeMb = maxFileSizeMb; }

    public void setInstallUncaughtHandler(boolean installUncaughtHandler) { this.installUncaughtHandler = installUncaughtHandler; }

    public void setReportErrorsWithoutThrowable(boolean reportErrorsWithoutThrowable) { this.reportErrorsWithoutThrowable = reportErrorsWithoutThrowable; }

    public void setCorrelationMdcKeys(String correlationMdcKeys) { this.correlationMdcKeys = correlationMdcKeys; }

    public void setZone(String zone) { this.zone = zone; }
}
