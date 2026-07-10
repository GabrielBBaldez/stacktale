package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Hostile-input edges: empty stacks, null messages, circular cause chains. */
class StackDistillerEdgeTest {

    /** getCause() is overridable — a hostile/buggy throwable can produce a real cycle. */
    private static final class Cyclic extends RuntimeException {
        private Throwable other;

        Cyclic(String msg) {
            super(msg);
            setStackTrace(new StackTraceElement[0]);
        }

        @Override
        public synchronized Throwable getCause() {
            return other;
        }
    }

    @Test
    void survivesEmptyStackTrace() {
        RuntimeException e = new RuntimeException("no frames");
        e.setStackTrace(new StackTraceElement[0]);

        DistilledStack d = new StackDistiller(List.of()).distill(e);

        assertThat(d.rootType()).isEqualTo("RuntimeException");
        assertThat(d.culpritLine()).isNull();
        assertThat(d.frameLines()).isEmpty();
        assertThat(d.totalFrames()).isZero();
    }

    @Test
    void survivesNullMessage() {
        RuntimeException e = new RuntimeException((String) null);
        e.setStackTrace(new StackTraceElement[]{new StackTraceElement("com.acme.A", "m", "A.java", 1)});

        DistilledStack d = new StackDistiller(List.of()).distill(e);

        assertThat(d.rootMessage()).isNull();
    }

    @Test
    void terminatesOnCircularCauseChains() {
        Cyclic a = new Cyclic("a");
        Cyclic b = new Cyclic("b");
        a.other = b;
        b.other = a;

        StackDistiller distiller = new StackDistiller(List.of());
        assertThatCode(() -> distiller.distill(a)).doesNotThrowAnyException();
    }
}
