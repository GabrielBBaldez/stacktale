package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StoryBufferTest {

    static LogEventData event(String logger, String level, String msg, long ts, Map<String, String> mdc) {
        return new LogEventData(ts, level, "ERROR".equals(level), logger,
                Thread.currentThread().getName(), msg, null, msg, mdc, null);
    }

    @Test
    void keepsOnlyLastNEntriesPerThread() {
        StoryBuffer buf = new StoryBuffer(3, 60_000, List.of("traceId"), 200);
        for (int i = 1; i <= 5; i++) buf.record(event("com.acme.A", "INFO", "m" + i, 1000 + i, Map.of()));
        LogEventData err = event("com.acme.A", "ERROR", "boom", 1010, Map.of());
        buf.record(err);
        Story story = buf.storyFor(err);
        assertThat(story.entries()).extracting(StoryEntry::message).containsExactly("m4", "m5", "boom");
        assertThat(story.contextLabel()).startsWith("thread ");
    }

    @Test
    void groupsByCorrelationKeyAcrossThreads() throws Exception {
        StoryBuffer buf = new StoryBuffer(10, 60_000, List.of("traceId"), 200);
        Map<String, String> mdc = Map.of("traceId", "9f3a");
        Thread t = new Thread(() -> buf.record(event("com.acme.B", "INFO", "from-other-thread", 1000, mdc)));
        t.start();
        t.join();
        LogEventData err = event("com.acme.A", "ERROR", "boom", 1001, mdc);
        buf.record(err);
        Story story = buf.storyFor(err);
        assertThat(story.entries()).extracting(StoryEntry::message).containsExactly("from-other-thread", "boom");
        assertThat(story.contextLabel()).isEqualTo("traceId=9f3a");
    }

    @Test
    void dropsEntriesOutsideTimeWindow() {
        StoryBuffer buf = new StoryBuffer(10, 1_000, List.of(), 200);
        buf.record(event("com.acme.A", "INFO", "old", 1000, Map.of()));
        LogEventData err = event("com.acme.A", "ERROR", "boom", 5000, Map.of());
        buf.record(err);
        assertThat(buf.storyFor(err).entries()).extracting(StoryEntry::message).containsExactly("boom");
    }

    @Test
    void truncatesLongMessagesAndShortensLogger() {
        StoryBuffer buf = new StoryBuffer(10, 60_000, List.of(), 20);
        buf.record(event("com.acme.deep.OrderService", "INFO", "x".repeat(50), 1000, Map.of()));
        LogEventData err = event("com.acme.A", "ERROR", "boom", 1001, Map.of());
        buf.record(err);
        StoryEntry first = buf.storyFor(err).entries().get(0);
        assertThat(first.message()).hasSize(21).endsWith("…");
        assertThat(first.logger()).isEqualTo("OrderService");
    }
}
