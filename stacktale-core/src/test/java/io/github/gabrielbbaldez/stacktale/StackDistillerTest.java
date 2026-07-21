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

    @Test
    void collapsesSelfRecursionIntoOneMarker() {
        StackTraceElement[] frames = new StackTraceElement[1024];
        for (int i = 0; i < 1000; i++) frames[i] = el("com.acme.PricingService", "discountFor", "PricingService.java", 51);
        for (int i = 1000; i < 1024; i++) frames[i] = el("com.acme.Main", "m" + i, "Main.java", i);
        StackOverflowError e = withStack(new StackOverflowError(), frames);

        DistilledStack d = new StackDistiller(List.of("com.acme")).distill(e);

        assertThat(d.frameLines().get(0))
                .contains("PricingService.discountFor(PricingService.java:51)")
                .contains("← culprit");
        assertThat(d.frameLines().get(1)).isEqualTo("… 999 recursive frames (PricingService.discountFor)");
        // One visible frame + one marker, not fifteen identical lines.
        assertThat(d.frameLines().stream()
                .filter(l -> l.contains("PricingService.discountFor(PricingService.java:51)"))
                .count()).isEqualTo(1);
    }

    @Test
    void collapsesMutualRecursionNamingTheCycle() {
        StackTraceElement[] frames = new StackTraceElement[100];
        for (int i = 0; i < 100; i += 2) {
            frames[i] = el("com.acme.OrderService", "confirm", "OrderService.java", 20);
            frames[i + 1] = el("com.acme.PricingService", "apply", "PricingService.java", 33);
        }
        StackOverflowError e = withStack(new StackOverflowError(), frames);

        DistilledStack d = new StackDistiller(List.of("com.acme")).distill(e);

        assertThat(d.frameLines().get(0)).contains("OrderService.confirm(OrderService.java:20)");
        assertThat(d.frameLines().get(1)).contains("PricingService.apply(PricingService.java:33)");
        assertThat(d.frameLines().get(2))
                .isEqualTo("… 98 recursive frames (OrderService.confirm → PricingService.apply → OrderService.confirm)");
    }

    @Test
    void doesNotCollapseLegitimateRepeats() {
        // A recursive tree walk of depth 2 plus a retry pair: methods repeat,
        // but never three full cycles in a row.
        RuntimeException e = withStack(new RuntimeException("x"),
                el("com.acme.Tree", "walk", "Tree.java", 12),
                el("com.acme.Tree", "walk", "Tree.java", 12),
                el("com.acme.Retry", "call", "Retry.java", 40),
                el("com.acme.Retry", "call", "Retry.java", 40),
                el("com.acme.Main", "main", "Main.java", 5));

        DistilledStack d = new StackDistiller(List.of("com.acme")).distill(e);

        assertThat(d.frameLines()).hasSize(5);
        assertThat(d.frameLines()).noneSatisfy(l -> assertThat(l).contains("recursive frames"));
    }

    @Test
    void collapsesARealStackOverflowError() {
        StackOverflowError e;
        try {
            recurse(0);
            throw new IllegalStateException("expected StackOverflowError");
        } catch (StackOverflowError caught) {
            e = caught;
        }

        DistilledStack d = new StackDistiller(List.of("io.github.gabrielbbaldez")).distill(e);

        assertThat(d.frameLines()).anySatisfy(l ->
                assertThat(l).contains("recursive frames").contains("StackDistillerTest.recurse"));
        // The report stays tiny even though the raw trace has hundreds of frames.
        assertThat(d.frameLines().size()).isLessThanOrEqualTo(6);
    }

    @Test
    void collapsesAtExactlyThreeCyclesButNotTwo() {
        // Pins the "≥ 3 cycles" threshold exactly: two identical frames render verbatim,
        // a third tips it into the collapsed marker.
        StackTraceElement loop = el("com.acme.Svc", "loop", "Svc.java", 9);
        StackTraceElement main = el("com.acme.Main", "main", "Main.java", 1);

        DistilledStack two = new StackDistiller(List.of("com.acme"))
                .distill(withStack(new RuntimeException("x"), loop, loop, main));
        assertThat(two.frameLines()).noneSatisfy(l -> assertThat(l).contains("recursive frames"));

        DistilledStack three = new StackDistiller(List.of("com.acme"))
                .distill(withStack(new RuntimeException("x"), loop, loop, loop, main));
        assertThat(three.frameLines()).anySatisfy(l -> assertThat(l).contains("recursive frames"));
    }

    private static int recurse(int depth) {
        return recurse(depth + 1);
    }
}
