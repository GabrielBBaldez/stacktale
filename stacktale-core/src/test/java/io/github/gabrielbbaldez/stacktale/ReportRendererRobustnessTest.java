package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** The renderer receives user-controlled data (args, messages, MDC) — it must survive all of it. */
class ReportRendererRobustnessTest {

    private static final Object POISON = new Object() {
        @Override public String toString() {
            throw new IllegalStateException("poisonous toString");
        }
    };

    private Report report(Object[] args, Map<String, String> mdc, Story story) {
        return new Report("abcd", 1_000_000L, "main", null,
                "processing {}", args, "com.acme.Svc", mdc, Map.of(), story, "app=? | java 21 | linux");
    }

    @Test
    void poisonousArgToStringDoesNotKillTheReport() {
        Report r = report(new Object[]{POISON}, Map.of(), new Story(List.of(), "thread main"));
        ReportRenderer renderer = new ReportRenderer(ZoneOffset.UTC);

        assertThatCode(() -> renderer.render(r)).doesNotThrowAnyException();
        String rendered = renderer.render(r);
        assertThat(rendered).contains("<toString failed: IllegalStateException>");
        assertThat(rendered).contains("━━━ END #abcd ━━━");
    }

    @Test
    void newlinesInUserDataAreFlattenedToKeepOneLinePerSection() {
        Story story = new Story(List.of(
                new StoryEntry(999_000L, "INFO", "Svc", "multi\nline\r\nstory message")
        ), "thread main");
        Report r = report(new Object[]{"va\nlue"}, Map.of("key", "with\nnewline"), story);

        String rendered = new ReportRenderer(ZoneOffset.UTC).render(r);

        assertThat(rendered).contains("mdc: key=with\\nnewline");
        assertThat(rendered).contains("args=[va\\nlue]");
        assertThat(rendered).contains("multi\\nline\\nstory message");
        // every content line must belong to the format: no stray lines from embedded newlines
        assertThat(rendered.lines().filter(l -> l.equals("lue") || l.equals("line") || l.equals("newline"))).isEmpty();
    }

    @Test
    void newlineInRootMessageIsFlattened() {
        DistilledStack stack = new DistilledStack("IllegalStateException", "first\nsecond",
                "Svc.run(Svc.java:1)", true, List.of(), List.of("Svc.run(Svc.java:1) ← culprit"), 1, 1, List.of());
        Report r = new Report("abcd", 1_000_000L, "main", stack,
                "boom", null, "com.acme.Svc", Map.of(), Map.of(), new Story(List.of(), "thread main"),
                "app=? | java 21 | linux");

        String rendered = new ReportRenderer(ZoneOffset.UTC).render(r);

        assertThat(rendered).contains("IllegalStateException: first\\nsecond");
    }
}
