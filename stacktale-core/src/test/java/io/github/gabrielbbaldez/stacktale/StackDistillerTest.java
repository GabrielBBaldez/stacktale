package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StackDistillerTest {

    private static StackTraceElement el(String cls, String method, String file, int line) {
        return new StackTraceElement(cls, method, file, line);
    }

    private static <T extends Throwable> T withStack(T e, StackTraceElement... frames) {
        e.setStackTrace(frames);
        return e;
    }

    @Test
    void rootCauseFirstWithCulpritAndWrappers() {
        NullPointerException npe = withStack(new NullPointerException("customer is null"),
                el("com.acme.OrderService", "confirm", "OrderService.java", 87),
                el("com.acme.OrderController", "confirm", "OrderController.java", 34),
                el("org.springframework.web.method.support.InvocableHandlerMethod", "invoke", "InvocableHandlerMethod.java", 190));
        IllegalStateException wrapper = withStack(new IllegalStateException("confirm failed", npe),
                el("com.acme.OrderService", "confirm", "OrderService.java", 92));

        DistilledStack d = new StackDistiller(List.of("com.acme")).distill(wrapper);

        assertThat(d.rootType()).isEqualTo("NullPointerException");
        assertThat(d.rootMessage()).isEqualTo("customer is null");
        assertThat(d.culpritLine()).isEqualTo("OrderService.confirm(OrderService.java:87)");
        assertThat(d.wrappedBy()).containsExactly(
                "IllegalStateException(\"confirm failed\") at OrderService.confirm(OrderService.java:92)");
        assertThat(d.frameLines().get(0)).contains("OrderService.confirm(OrderService.java:87)").contains("← culprit");
        assertThat(d.totalFrames()).isEqualTo(3);
    }

    @Test
    void collapsesFrameworkRunsWithGroupCounts() {
        StackTraceElement[] frames = new StackTraceElement[8];
        frames[0] = el("com.acme.Svc", "run", "Svc.java", 10);
        for (int i = 1; i <= 4; i++) frames[i] = el("org.springframework.core.C" + i, "m", "C.java", i);
        for (int i = 5; i <= 6; i++) frames[i] = el("org.apache.catalina.T" + i, "m", "T.java", i);
        frames[7] = el("com.acme.Main", "main", "Main.java", 5);
        RuntimeException e = withStack(new RuntimeException("x"), frames);

        DistilledStack d = new StackDistiller(List.of("com.acme")).distill(e);

        assertThat(d.frameLines()).anySatisfy(l ->
                assertThat(l).contains("… 6 collapsed").contains("spring ×4").contains("tomcat ×2"));
        assertThat(d.shownFrames()).isEqualTo(2);
        assertThat(d.totalFrames()).isEqualTo(8);
    }

    @Test
    void alwaysShowsThrowingFrameEvenIfFramework() {
        RuntimeException e = withStack(new RuntimeException("deep"),
                el("java.util.HashMap", "hash", "HashMap.java", 338),
                el("com.acme.Svc", "run", "Svc.java", 10));
        DistilledStack d = new StackDistiller(List.of("com.acme")).distill(e);
        assertThat(d.frameLines().get(0)).contains("HashMap.hash(HashMap.java:338)");
        assertThat(d.culpritLine()).isEqualTo("Svc.run(Svc.java:10)");
        assertThat(d.culpritIsAppCode()).isTrue();
    }

    @Test
    void fallbackCulpritIsNotClaimedAsAppCode() {
        // no app frame anywhere in the stack (e.g. a framework binding error)
        RuntimeException e = withStack(new RuntimeException("binding failed"),
                el("org.springframework.web.method.support.HandlerMethodArgumentResolver", "resolve", "H.java", 186),
                el("org.apache.catalina.core.ApplicationFilterChain", "doFilter", "A.java", 166));
        DistilledStack d = new StackDistiller(List.of("com.acme")).distill(e);
        assertThat(d.culpritLine()).isEqualTo("HandlerMethodArgumentResolver.resolve(H.java:186)");
        assertThat(d.culpritIsAppCode()).isFalse();
    }

    @Test
    void heuristicModeTreatsNonFrameworkAsApp() {
        RuntimeException e = withStack(new RuntimeException("x"),
                el("org.springframework.core.C", "m", "C.java", 1),
                el("com.whatever.Foo", "bar", "Foo.java", 2));
        DistilledStack d = new StackDistiller(List.of()).distill(e);
        assertThat(d.culpritLine()).isEqualTo("Foo.bar(Foo.java:2)");
    }

    @Test
    void survivesDeepCauseChains() {
        RuntimeException deep = new RuntimeException("lvl0");
        RuntimeException cur = deep;
        for (int i = 1; i <= 15; i++) {
            cur = new RuntimeException("lvl" + i, cur);
        }
        DistilledStack d = new StackDistiller(List.of()).distill(cur);
        assertThat(d.rootType()).isEqualTo("RuntimeException");
        assertThat(d.wrappedBy()).hasSizeLessThanOrEqualTo(9); // depth capped at 10 total
    }

    @Test
    void rendersSuppressedExceptions() {
        RuntimeException withSup = withStack(new RuntimeException("s"), el("com.acme.A", "m", "A.java", 1));
        withSup.addSuppressed(new IllegalArgumentException("cleanup failed"));
        DistilledStack d = new StackDistiller(List.of()).distill(withSup);
        assertThat(d.suppressed()).hasSize(1);
        assertThat(d.suppressed().get(0)).contains("IllegalArgumentException").contains("cleanup failed");
    }
}
