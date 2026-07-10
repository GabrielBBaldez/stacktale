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
        if (s.count == 0 || now - s.lastReport > windowMillis) {
            s.count = 1;
            s.lastWrittenCount = 1;
            s.lastReport = now;
            s.lastSummary = now;
            return new Decision(Kind.REPORT, 1, now);
        }
        s.count++;
        boolean firstRepeat = s.count == 2;
        if (firstRepeat || now - s.lastSummary >= summaryThrottleMillis) {
            s.lastSummary = now;
            // lastWrittenCount is advanced only after the summary is durably written
            // (confirmWritten) — otherwise a failed write would silently lose the count
            return new Decision(Kind.SUMMARY, s.count, now);
        }
        return new Decision(Kind.SILENT, s.count, now);
    }

    /** Records that a summary line reflecting {@code count} reached the file. */
    synchronized void confirmWritten(String fingerprint, int count) {
        Stats s = stats.get(fingerprint);
        if (s != null && count > s.lastWrittenCount) s.lastWrittenCount = count;
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
