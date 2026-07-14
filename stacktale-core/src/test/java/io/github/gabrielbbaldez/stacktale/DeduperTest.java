package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class DeduperTest {

    @Test
    void firstIsReportRepeatsSummarizedThenThrottled() {
        AtomicLong now = new AtomicLong(0);
        Deduper d = new Deduper(300_000, 60_000, now::get);

        assertThat(d.decide("a1").kind()).isEqualTo(Kind.REPORT);
        d.confirmReport("a1"); // the report reached the file

        now.set(1_000);
        Decision second = d.decide("a1");
        assertThat(second.kind()).isEqualTo(Kind.SUMMARY);
        assertThat(second.count()).isEqualTo(2);

        now.set(2_000);
        assertThat(d.decide("a1").kind()).isEqualTo(Kind.SILENT);

        now.set(62_000);
        Decision later = d.decide("a1");
        assertThat(later.kind()).isEqualTo(Kind.SUMMARY);
        assertThat(later.count()).isEqualTo(4);
    }

    @Test
    void newReportAfterWindowExpires() {
        AtomicLong now = new AtomicLong(0);
        Deduper d = new Deduper(300_000, 60_000, now::get);
        d.decide("a1");
        now.set(300_001);
        Decision again = d.decide("a1");
        assertThat(again.kind()).isEqualTo(Kind.REPORT);
        assertThat(again.count()).isEqualTo(1);
    }

    @Test
    void independentFingerprints() {
        Deduper d = new Deduper(300_000, 60_000, () -> 0);
        d.decide("a1");
        assertThat(d.decide("b2").kind()).isEqualTo(Kind.REPORT);
    }

    @Test
    void drainPendingSurfacesThrottledBurstCounts() {
        AtomicLong now = new AtomicLong(0);
        Deduper d = new Deduper(300_000, 60_000, now::get);
        d.decide("a1");                       // REPORT (written: 1)
        d.confirmReport("a1");
        now.set(1_000);
        d.decide("a1");                       // SUMMARY (written: 2)
        now.set(2_000);
        d.decide("a1");                       // SILENT
        d.decide("a1");                       // SILENT (count now 4, file still says 2)
        var pending = d.drainPending();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).count()).isEqualTo(4);
        assertThat(d.drainPending()).isEmpty(); // draining is idempotent
    }

    @Test
    void rollbackGivesTheNextOccurrenceAFreshReport() {
        AtomicLong now = new AtomicLong(0);
        Deduper d = new Deduper(300_000, 60_000, now::get);

        assertThat(d.decide("a1").kind()).isEqualTo(Kind.REPORT);
        d.rollback("a1"); // the report write failed
        now.set(1_000);
        // without rollback this would be SUMMARY; the error must get a fresh chance
        assertThat(d.decide("a1").kind()).isEqualTo(Kind.REPORT);
    }

    @Test
    void pendingReportSilencesRepeatsThenRollbackReArmsAFreshReport() {
        // #51: while a report is decided-but-not-yet-written, repeats stay SILENT — never a
        // SUMMARY referencing a report the file may never receive. If it was storm-suppressed
        // (rollback), the next occurrence gets a fresh REPORT; if it was written (confirmReport),
        // repeats summarize normally.
        AtomicLong now = new AtomicLong(0);
        Deduper d = new Deduper(300_000, 60_000, now::get);

        assertThat(d.decide("a1").kind()).isEqualTo(Kind.REPORT);  // pending, not yet written
        now.set(1_000);
        assertThat(d.decide("a1").kind()).isEqualTo(Kind.SILENT);  // no orphan SUMMARY while pending

        d.rollback("a1");                                          // storm-suppressed / write failed
        now.set(2_000);
        assertThat(d.decide("a1").kind()).isEqualTo(Kind.REPORT);  // re-armed: fresh report next time
        d.confirmReport("a1");                                     // this one reached the file
        now.set(3_000);
        assertThat(d.decide("a1").kind()).isEqualTo(Kind.SUMMARY); // now repeats summarize
    }
}
