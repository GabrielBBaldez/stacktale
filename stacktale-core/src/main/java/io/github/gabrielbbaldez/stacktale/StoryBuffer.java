package io.github.gabrielbbaldez.stacktale;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded ring buffers of recent log events. Events carrying a correlation MDC key are
 * grouped by that key (so the story survives thread hops); everything else falls back to
 * a per-thread ring. Old contexts are evicted LRU; old entries fall out of the ring and
 * out of the time window.
 */
final class StoryBuffer {

    private static final int MAX_CONTEXTS = 256;

    private final int capacity;
    private final long windowMillis;
    private final List<String> correlationKeys;
    private final int maxMessageLength;

    private final ThreadLocal<Deque<StoryEntry>> perThread = ThreadLocal.withInitial(ArrayDeque::new);
    private final Map<String, Deque<StoryEntry>> perCorrelation =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deque<StoryEntry>> e) {
                    return size() > MAX_CONTEXTS;
                }
            };

    StoryBuffer(int capacity, long windowMillis, List<String> correlationKeys, int maxMessageLength) {
        this.capacity = capacity;
        this.windowMillis = windowMillis;
        this.correlationKeys = correlationKeys;
        this.maxMessageLength = maxMessageLength;
    }

    void record(LogEventData event) {
        StoryEntry entry = toEntry(event);
        String key = correlationKey(event);
        if (key != null) {
            synchronized (perCorrelation) {
                push(perCorrelation.computeIfAbsent(key, k -> new ArrayDeque<>()), entry);
            }
        } else {
            push(perThread.get(), entry);
        }
    }

    Story storyFor(LogEventData errorEvent) {
        long cutoff = errorEvent.epochMillis() - windowMillis;
        String key = correlationKey(errorEvent);
        List<StoryEntry> snapshot;
        String label;
        if (key != null) {
            synchronized (perCorrelation) {
                Deque<StoryEntry> deque = perCorrelation.get(key);
                snapshot = deque == null ? List.of() : new ArrayList<>(deque);
            }
            label = key;
        } else {
            Deque<StoryEntry> deque = perThread.get();
            synchronized (deque) {
                snapshot = new ArrayList<>(deque);
            }
            label = "thread " + errorEvent.threadName();
        }
        return new Story(snapshot.stream().filter(e -> e.epochMillis() >= cutoff).toList(), label);
    }

    private void push(Deque<StoryEntry> deque, StoryEntry entry) {
        synchronized (deque) {
            if (deque.size() >= capacity) deque.pollFirst();
            deque.addLast(entry);
        }
    }

    private StoryEntry toEntry(LogEventData event) {
        String logger = event.loggerName();
        int dot = logger.lastIndexOf('.');
        if (dot >= 0) logger = logger.substring(dot + 1);
        String msg = String.valueOf(event.formattedMessage());
        if (msg.length() > maxMessageLength) msg = msg.substring(0, maxMessageLength) + "…";
        return new StoryEntry(event.epochMillis(), event.level(), logger, msg);
    }

    private String correlationKey(LogEventData event) {
        Map<String, String> mdc = event.mdc();
        if (mdc == null || mdc.isEmpty()) return null;
        for (String k : correlationKeys) {
            String v = mdc.get(k);
            if (v != null && !v.isBlank()) return k + "=" + v;
        }
        return null;
    }
}
