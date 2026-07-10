package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FieldExtractorTest {

    enum Status { REJECTED }

    static class OrderException extends RuntimeException {
        private final int orderId = 889;
        public final String sku = "sku-4411";

        OrderException(String msg) { super(msg); }

        public int getOrderId() { return orderId; }
        public boolean isRetryable() { return false; }
        public Status getStatus() { return Status.REJECTED; }
        public String getNullable() { return null; }
        public Object getComplex() { return new Object(); }          // non-value type → skipped
        public String getPoisonous() { throw new IllegalStateException("boom"); } // throwing → skipped
        public String getHuge() { return "x".repeat(300); }          // truncated
        String getPackagePrivate() { return "hidden"; }              // not public → skipped
        public static String getStaticThing() { return "static"; }   // static → skipped
    }

    @Test
    void extractsValueTypedGettersAndPublicFields() {
        Map<String, String> fields = FieldExtractor.extract(new OrderException("x"));
        assertThat(fields)
                .containsEntry("orderId", "889")
                .containsEntry("retryable", "false")
                .containsEntry("status", "REJECTED")
                .containsEntry("nullable", "null")
                .containsEntry("sku", "sku-4411")
                .doesNotContainKeys("complex", "poisonous", "packagePrivate", "staticThing");
        assertThat(fields.get("huge")).hasSize(81).endsWith("…");
    }

    @Test
    void skipsThrowableBuiltInsAndPlainExceptions() {
        // getMessage/getCause/getLocalizedMessage/getStackTrace/getSuppressed must not leak in
        Map<String, String> fields = FieldExtractor.extract(new IllegalStateException("just a message"));
        assertThat(fields).isEmpty();
    }

    @Test
    void capsAtEightFields() {
        class Wide extends RuntimeException {
            public int getA() { return 1; }
            public int getB() { return 2; }
            public int getC() { return 3; }
            public int getD() { return 4; }
            public int getE() { return 5; }
            public int getF() { return 6; }
            public int getG() { return 7; }
            public int getH() { return 8; }
            public int getI() { return 9; }
            public int getJ() { return 10; }
        }
        assertThat(FieldExtractor.extract(new Wide())).hasSize(8);
    }

    @Test
    void neverThrows() {
        assertThat(FieldExtractor.extract(null)).isEmpty();
        assertThat(FieldExtractor.extractChain(null)).isEmpty();
    }

    @Test
    void chainMergesWrapperAndRootFields() {
        // the domain wrapper carries the business state; the root is a bare NPE
        OrderException wrapper = new OrderException("confirm failed");
        wrapper.initCause(new NullPointerException("customer is null"));
        Map<String, String> fields = FieldExtractor.extractChain(wrapper);
        assertThat(fields).containsEntry("orderId", "889").containsEntry("retryable", "false");
    }
}
