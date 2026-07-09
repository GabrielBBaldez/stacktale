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
    void rollbackGivesTheNextOccurrenceAFreshReport() {
        AtomicLong now = new AtomicLong(0);
        Deduper d = new Deduper(300_000, 60_000, now::get);

        assertThat(d.decide("a1").kind()).isEqualTo(Kind.REPORT);
        d.rollback("a1"); // the report write failed
        now.set(1_000);
        // without rollback this would be SUMMARY; the error must get a fresh chance
        assertThat(d.decide("a1").kind()).isEqualTo(Kind.REPORT);
    }
}
