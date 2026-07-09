package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprinterTest {

    @Test
    void sameErrorSameIdEvenWithDifferentNumbersInMessage() {
        String a = Fingerprinter.fingerprint("NullPointerException", "OrderService.confirm(OrderService.java:87)", "failed order 123");
        String b = Fingerprinter.fingerprint("NullPointerException", "OrderService.confirm(OrderService.java:87)", "failed order 456");
        assertThat(a).isEqualTo(b).hasSize(8).matches("[0-9a-f]{8}");
    }

    @Test
    void differentLineDifferentId() {
        String a = Fingerprinter.fingerprint("NullPointerException", "OrderService.confirm(OrderService.java:87)", "x");
        String b = Fingerprinter.fingerprint("NullPointerException", "OrderService.confirm(OrderService.java:88)", "x");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hexAddressesNormalized() {
        String a = Fingerprinter.fingerprint("OutOfMemoryError", "A.m(A.java:1)", "direct buffer at 0xdeadbeef");
        String b = Fingerprinter.fingerprint("OutOfMemoryError", "A.m(A.java:1)", "direct buffer at 0xcafebabe");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void nullSafe() {
        assertThat(Fingerprinter.fingerprint(null, null, null)).hasSize(8);
    }
}
