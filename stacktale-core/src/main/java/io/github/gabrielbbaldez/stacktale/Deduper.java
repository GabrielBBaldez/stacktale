package io.github.gabrielbbaldez.stacktale;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Decides whether an error occurrence deserves a full report, a short repeat summary, or
 * silence. A fingerprint gets a fresh report once per window; repeats within the window
 * are counted and surfaced as throttled summary lines.
 */
final class Deduper {

    private static final int MAX_FINGERPRINTS = 1024;

    private static final class Stats {
        int count;
        int lastWrittenCount;
        long lastReport;
        long lastSummary;
        long lastSeen;
        long firstSeen;    // first time this fingerprint was seen this session (never reset)
        int total;         // lifetime occurrences across all windows
        boolean reportPending; // a REPORT was decided but not yet durably written (retry it)
    }

    /** A repeat counter whose latest value never reached the file (burst inside the throttle). */
    record Pending(String fingerprint, int count, long lastSeenMillis) {}

    private final long windowMillis;
    private final long summaryThrottleMillis;
    private final LongSupplier clock;
    private final Map<String, Stats> stats = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Stats> e) {
            return size() > MAX_FINGERPRINTS;
        }
    };

    Deduper(long windowMillis, long summaryThrottleMillis, LongSupplier clock) {
        this.windowMillis = windowMillis;
        this.summaryThrottleMillis = summaryThrottleMillis;
        this.clock = clock;
    }

    synchronized Decision decide(String fingerprint) {
        long now = clock.getAsLong();
        Stats s = stats.computeIfAbsent(fingerprint, k -> new Stats());
        s.lastSeen = now;
        if (s.total == 0) s.firstSeen = now;
        s.total++;
        // A fresh full report: first sighting in the window, or the window rolled over.
        if (s.count == 0 || now - s.lastReport > windowMillis) {
            s.count = 1;
            s.lastWrittenCount = 1;
            s.lastReport = now;
            s.lastSummary = now;
            s.reportPending = true;
            return new Decision(Kind.REPORT, 1, now, s.total, s.firstSeen);
        }
        s.count++;
        // The window's report was decided but is not yet durably written — a write is in
        // flight (concurrency), or it was storm-suppressed and not yet re-armed. Stay SILENT:
        // never emit a SUMMARY that references a report the file may never receive (#51). The
        // count still advances; confirmReport resumes summaries, rollback re-arms a REPORT.
        if (s.reportPending) {
            return new Decision(Kind.SILENT, s.count, now, s.total, s.firstSeen);
        }
        boolean firstRepeat = s.count == 2;
        if (firstRepeat || now - s.lastSummary >= summaryThrottleMillis) {
            s.lastSummary = now;
            // lastWrittenCount is advanced only after the summary is durably written
            // (confirmWritten) — otherwise a failed write would silently lose the count
            return new Decision(Kind.SUMMARY, s.count, now, s.total, s.firstSeen);
        }
        return new Decision(Kind.SILENT, s.count, now, s.total, s.firstSeen);
    }

    /** Records that a summary line reflecting {@code count} reached the file. */
    synchronized void confirmWritten(String fingerprint, int count) {
        Stats s = stats.get(fingerprint);
        if (s != null && count > s.lastWrittenCount) s.lastWrittenCount = count;
    }

    /** The full report for this fingerprint reached the file; stop retrying it as a REPORT. */
    synchronized void confirmReport(String fingerprint) {
        Stats s = stats.get(fingerprint);
        if (s != null) s.reportPending = false;
    }

    /** Counters ahead of what the file shows (throttled bursts) — drained on shutdown. */
    synchronized java.util.List<Pending> drainPending() {
        java.util.List<Pending> pending = new java.util.ArrayList<>();
        for (Map.Entry<String, Stats> e : stats.entrySet()) {
            Stats s = e.getValue();
            if (s.count > s.lastWrittenCount) {
                pending.add(new Pending(e.getKey(), s.count, s.lastSeen));
                s.lastWrittenCount = s.count;
            }
        }
        return pending;
    }

    /**
     * A REPORT decision was handed out but the report could not be written (e.g. a
     * transient I/O failure). Forget the fingerprint so the next occurrence gets a fresh
     * REPORT instead of being silently summarized for the rest of the window.
     */
    synchronized void rollback(String fingerprint) {
        stats.remove(fingerprint);
    }
}
