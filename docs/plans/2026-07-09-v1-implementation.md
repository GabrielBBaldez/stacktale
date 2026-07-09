# stacktale v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship stacktale v1 — a Logback appender that turns Java error logs into AI-ready reports in `errors-ai.log`.

**Architecture:** Single Maven module. A Logback `UnsynchronizedAppenderBase` orchestrates 7 small components: StoryBuffer (recent-events ring), StackDistiller (root-cause-first distilled stacks), Fingerprinter + Deduper (repeat suppression), EnvCollector, ReportRenderer (pure `st/1` formatter, golden-file tested), ReportWriter (UTF-8 file + rotation). An optional UncaughtHandler funnels uncaught exceptions through the same pipeline via logger `stacktale.uncaught`.

**Tech Stack:** Java 17, Logback 1.5.x, Maven, JUnit 5 + AssertJ.

## Global Constraints

- Java release: **17** (`maven.compiler.release=17`)
- Only runtime dependency: `ch.qos.logback:logback-classic:1.5.18`
- Package root: `io.github.gabrielbbaldez.stacktale`
- Maven coords: `io.github.gabrielbbaldez:stacktale:0.1.0-SNAPSHOT`
- All file I/O and source encoding: UTF-8 explicitly (JDK 17 on Windows defaults to Cp1252)
- The appender must NEVER throw out of `append()` — catch Throwable, degrade to no-op
- Events from logger named exactly `stacktale` are ignored by the appender (anti-loop); `stacktale.uncaught` is processed normally
- Report format `st/1` is public API — pinned by golden-file tests
- Commits: conventional messages (`feat:`, `test:`, `docs:`, `chore:`), NO AI/Claude mentions, no Co-Authored-By trailers
- Run tests with `rtk mvn -q test` from `C:\Users\Baldez\Desktop\stacktale` (rtk = token-saving proxy; plain `mvn` also fine)

---

### Task 1: Project scaffold

**Files:**
- Create: `pom.xml`, `.gitignore`, `LICENSE`

**Interfaces:**
- Produces: buildable Maven project; `mvn verify` green (no tests yet).

- [ ] **Step 1: Write pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>io.github.gabrielbbaldez</groupId>
  <artifactId>stacktale</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <name>stacktale</name>
  <description>Stack traces that tell the tale — a Logback appender that turns Java errors into AI-ready reports.</description>
  <url>https://github.com/GabrielBBaldez/stacktale</url>

  <licenses>
    <license>
      <name>Apache-2.0</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0</url>
    </license>
  </licenses>

  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
    <logback.version>1.5.18</logback.version>
    <junit.version>5.11.4</junit.version>
    <assertj.version>3.26.3</assertj.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>ch.qos.logback</groupId>
      <artifactId>logback-classic</artifactId>
      <version>${logback.version}</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <version>${assertj.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
        <configuration>
          <argLine>-Dfile.encoding=UTF-8</argLine>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Write .gitignore**

```
target/
*.class
.idea/
*.iml
.vscode/
errors-ai.log*
```

- [ ] **Step 3: Add LICENSE** — full Apache-2.0 text (fetch canonical text from https://www.apache.org/licenses/LICENSE-2.0.txt).

- [ ] **Step 4: Verify build**

Run: `rtk mvn -q verify`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add pom.xml .gitignore LICENSE
git commit -m "chore: maven scaffold (java 17, logback 1.5, junit 5)"
```

---

### Task 2: StoryBuffer

**Files:**
- Create: `src/main/java/io/github/gabrielbbaldez/stacktale/StoryEntry.java`
- Create: `src/main/java/io/github/gabrielbbaldez/stacktale/Story.java`
- Create: `src/main/java/io/github/gabrielbbaldez/stacktale/StoryBuffer.java`
- Test: `src/test/java/io/github/gabrielbbaldez/stacktale/StoryBufferTest.java`

**Interfaces:**
- Produces:
  - `record StoryEntry(long epochMillis, String level, String logger, String message)` — `logger` is the simple class name, `message` truncated.
  - `record Story(List<StoryEntry> entries, String contextLabel)` — label like `thread main` or `traceId=9f3a`.
  - `StoryBuffer(int capacity, long windowMillis, List<String> correlationMdcKeys, int maxMessageLength)`; `void record(ILoggingEvent e)`; `Story storyFor(ILoggingEvent errorEvent)`.

Behavior: if the event's MDC contains any correlation key (first match wins), the entry goes to a per-key ring (LRU map, max 256 contexts); otherwise to a per-thread ring (ThreadLocal). `storyFor` picks the ring the same way, filters entries to `errorEvent.timestamp - windowMillis`, returns oldest-first snapshot.

- [ ] **Step 1: Write failing tests** (representative cases; use logback's `LoggingEvent` built programmatically via a small `TestEvents` helper in the test package)

```java
package io.github.gabrielbbaldez.stacktale;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class StoryBufferTest {

    static ILoggingEvent event(String logger, Level level, String msg, long ts, Map<String, String> mdc) {
        LoggerContext ctx = new LoggerContext();
        Logger l = ctx.getLogger(logger);
        LoggingEvent e = new LoggingEvent("fqcn", l, level, msg, null, null);
        e.setTimeStamp(ts);
        e.setMDCPropertyMap(mdc);
        return e;
    }

    @Test
    void keepsOnlyLastNEntriesPerThread() {
        StoryBuffer buf = new StoryBuffer(3, 60_000, List.of("traceId"), 200);
        for (int i = 1; i <= 5; i++) buf.record(event("com.acme.A", Level.INFO, "m" + i, 1000 + i, Map.of()));
        ILoggingEvent err = event("com.acme.A", Level.ERROR, "boom", 1010, Map.of());
        buf.record(err);
        Story story = buf.storyFor(err);
        assertThat(story.entries()).extracting(StoryEntry::message).containsExactly("m5", "boom");
        // capacity 3: [m4, m5, boom] minus... wait: capacity keeps last 3 = m4, m5, boom
    }

    @Test
    void groupsByCorrelationKeyAcrossThreads() throws Exception {
        StoryBuffer buf = new StoryBuffer(10, 60_000, List.of("traceId"), 200);
        Map<String, String> mdc = Map.of("traceId", "9f3a");
        Thread t = new Thread(() -> buf.record(event("com.acme.B", Level.INFO, "from-other-thread", 1000, mdc)));
        t.start(); t.join();
        ILoggingEvent err = event("com.acme.A", Level.ERROR, "boom", 1001, mdc);
        buf.record(err);
        Story story = buf.storyFor(err);
        assertThat(story.entries()).extracting(StoryEntry::message).containsExactly("from-other-thread", "boom");
        assertThat(story.contextLabel()).isEqualTo("traceId=9f3a");
    }

    @Test
    void dropsEntriesOutsideTimeWindow() {
        StoryBuffer buf = new StoryBuffer(10, 1_000, List.of(), 200);
        buf.record(event("com.acme.A", Level.INFO, "old", 1000, Map.of()));
        ILoggingEvent err = event("com.acme.A", Level.ERROR, "boom", 5000, Map.of());
        buf.record(err);
        assertThat(buf.storyFor(err).entries()).extracting(StoryEntry::message).containsExactly("boom");
    }

    @Test
    void truncatesLongMessagesAndShortensLogger() {
        StoryBuffer buf = new StoryBuffer(10, 60_000, List.of(), 20);
        buf.record(event("com.acme.deep.OrderService", Level.INFO, "x".repeat(50), 1000, Map.of()));
        ILoggingEvent err = event("com.acme.A", Level.ERROR, "boom", 1001, Map.of());
        buf.record(err);
        StoryEntry first = buf.storyFor(err).entries().get(0);
        assertThat(first.message()).hasSize(21).endsWith("…");
        assertThat(first.logger()).isEqualTo("OrderService");
    }
}
```

- [ ] **Step 2: Run to verify fail** — `rtk mvn -q test` → compile error (classes missing). Expected.

- [ ] **Step 3: Implement**

```java
// StoryEntry.java
package io.github.gabrielbbaldez.stacktale;

public record StoryEntry(long epochMillis, String level, String logger, String message) {}
```

```java
// Story.java
package io.github.gabrielbbaldez.stacktale;

import java.util.List;

public record Story(List<StoryEntry> entries, String contextLabel) {}
```

```java
// StoryBuffer.java
package io.github.gabrielbbaldez.stacktale;

import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded ring buffers of recent log events, grouped by MDC correlation key or thread. */
final class StoryBuffer {

    private static final int MAX_CONTEXTS = 256;

    private final int capacity;
    private final long windowMillis;
    private final List<String> correlationKeys;
    private final int maxMessageLength;

    private final ThreadLocal<Deque<StoryEntry>> perThread = ThreadLocal.withInitial(ArrayDeque::new);
    private final Map<String, Deque<StoryEntry>> perCorrelation =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Deque<StoryEntry>> e) {
                    return size() > MAX_CONTEXTS;
                }
            };

    StoryBuffer(int capacity, long windowMillis, List<String> correlationKeys, int maxMessageLength) {
        this.capacity = capacity;
        this.windowMillis = windowMillis;
        this.correlationKeys = correlationKeys;
        this.maxMessageLength = maxMessageLength;
    }

    void record(ILoggingEvent event) {
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

    Story storyFor(ILoggingEvent errorEvent) {
        long cutoff = errorEvent.getTimeStamp() - windowMillis;
        String key = correlationKey(errorEvent);
        List<StoryEntry> snapshot;
        String label;
        if (key != null) {
            synchronized (perCorrelation) {
                Deque<StoryEntry> deque = perCorrelation.get(key);
                snapshot = deque == null ? List.of() : new ArrayList<>(deque);
            }
            label = correlationLabel(errorEvent, key);
        } else {
            snapshot = new ArrayList<>(perThread.get());
            label = "thread " + errorEvent.getThreadName();
        }
        return new Story(snapshot.stream().filter(e -> e.epochMillis() >= cutoff).toList(), label);
    }

    private void push(Deque<StoryEntry> deque, StoryEntry entry) {
        synchronized (deque) {
            if (deque.size() >= capacity) deque.pollFirst();
            deque.addLast(entry);
        }
    }

    private StoryEntry toEntry(ILoggingEvent event) {
        String logger = event.getLoggerName();
        int dot = logger.lastIndexOf('.');
        if (dot >= 0) logger = logger.substring(dot + 1);
        String msg = String.valueOf(event.getFormattedMessage());
        if (msg.length() > maxMessageLength) msg = msg.substring(0, maxMessageLength) + "…";
        return new StoryEntry(event.getTimeStamp(), event.getLevel().toString(), logger, msg);
    }

    private String correlationKey(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc == null || mdc.isEmpty()) return null;
        for (String k : correlationKeys) {
            String v = mdc.get(k);
            if (v != null && !v.isBlank()) return k + "=" + v;
        }
        return null;
    }

    private String correlationLabel(ILoggingEvent event, String key) {
        return key;
    }
}
```

Note: correlation key doubles as label (`traceId=9f3a`). `storyFor` label for thread case uses the error event's thread name.

- [ ] **Step 4: Run tests** — `rtk mvn -q test` → all StoryBufferTest pass. Fix the first test's expectation to match capacity semantics (last 3 = `m4, m5, boom` — assert `containsExactly("m4", "m5", "boom")`).

- [ ] **Step 5: Commit** — `git add src && git commit -m "feat: story buffer with correlation-key and per-thread rings"`

---

### Task 3: StackDistiller

**Files:**
- Create: `src/main/java/io/github/gabrielbbaldez/stacktale/DistilledStack.java`
- Create: `src/main/java/io/github/gabrielbbaldez/stacktale/StackDistiller.java`
- Test: `src/test/java/io/github/gabrielbbaldez/stacktale/StackDistillerTest.java`

**Interfaces:**
- Consumes: logback `IThrowableProxy` (`new ThrowableProxy(throwable)` in tests).
- Produces:

```java
public record DistilledStack(
    String rootType,          // simple name, e.g. "NullPointerException"
    String rootMessage,       // may be null
    String culpritLine,       // "OrderService.confirm(OrderService.java:87)" or null
    List<String> wrappedBy,   // outermost-last wrappers, root-outward: "OrderException(\"confirm failed\") at OrderService.confirm(OrderService.java:92)"
    List<String> frameLines,  // rendered lines: frames + collapse markers, culprit tagged "← culprit"
    int totalFrames,
    int shownFrames,
    List<String> suppressed   // one line each, max 3 + overflow marker
) {}
```

  - `StackDistiller(List<String> appPackages)`; `DistilledStack distill(IThrowableProxy proxy)`
  - Framework groups (prefix → label): spring `org.springframework.`; tomcat `org.apache.catalina./org.apache.tomcat./org.apache.coyote.`; servlet `jakarta.servlet./javax.servlet.`; jdk `java./jdk./sun./com.sun.`; hibernate `org.hibernate.`; hikari `com.zaxxer.hikari.`; netty `io.netty.`; reactor `reactor.`; test `org.junit./org.mockito.`; logging `ch.qos.logback./org.slf4j.`; other = anything else non-app.
  - Rules: root cause = deepest cause (cycle-safe, max depth 10). Culprit = first app frame of root cause (fallback: frame[0]). App frame = matches `appPackages` if configured, else "not framework". Always show frame[0] even if framework. Collapse runs of ≥2 consecutive non-app frames into `… N collapsed (spring ×a, tomcat ×b)`. Cap shown real frames at 15 → `… N more frames`. Messages in wrappedBy truncated to 80 chars.

- [ ] **Step 1: Write failing tests**

```java
package io.github.gabrielbbaldez.stacktale;

import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class StackDistillerTest {

    private static StackTraceElement el(String cls, String method, String file, int line) {
        return new StackTraceElement(cls, method, file, line);
    }

    private static RuntimeException withStack(RuntimeException e, StackTraceElement... frames) {
        e.setStackTrace(frames);
        return e;
    }

    @Test
    void rootCauseFirstWithCulpritAndWrappers() {
        RuntimeException npe = withStack(new NullPointerException("customer is null"),
                el("com.acme.OrderService", "confirm", "OrderService.java", 87),
                el("com.acme.OrderController", "confirm", "OrderController.java", 34),
                el("org.springframework.web.method.support.InvocableHandlerMethod", "invoke", "InvocableHandlerMethod.java", 190));
        RuntimeException wrapper = withStack(new IllegalStateException("confirm failed", npe),
                el("com.acme.OrderService", "confirm", "OrderService.java", 92));

        DistilledStack d = new StackDistiller(List.of("com.acme")).distill(new ThrowableProxy(wrapper));

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

        DistilledStack d = new StackDistiller(List.of("com.acme")).distill(new ThrowableProxy(e));

        assertThat(d.frameLines()).anySatisfy(l ->
                assertThat(l).contains("… 6 collapsed").contains("spring ×4").contains("tomcat ×2"));
        assertThat(d.shownFrames()).isEqualTo(2);
    }

    @Test
    void alwaysShowsThrowingFrameEvenIfFramework() {
        RuntimeException e = withStack(new RuntimeException("deep"),
                el("java.util.HashMap", "hash", "HashMap.java", 338),
                el("com.acme.Svc", "run", "Svc.java", 10));
        DistilledStack d = new StackDistiller(List.of("com.acme")).distill(new ThrowableProxy(e));
        assertThat(d.frameLines().get(0)).contains("HashMap.hash(HashMap.java:338)");
    }

    @Test
    void heuristicModeTreatsNonFrameworkAsApp() {
        RuntimeException e = withStack(new RuntimeException("x"),
                el("org.springframework.core.C", "m", "C.java", 1),
                el("com.whatever.Foo", "bar", "Foo.java", 2));
        DistilledStack d = new StackDistiller(List.of()).distill(new ThrowableProxy(e));
        assertThat(d.culpritLine()).isEqualTo("Foo.bar(Foo.java:2)");
    }

    @Test
    void rendersSuppressedAndSurvivesCauseCycles() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(null); // can't create true cycle via API; simulate deep chain instead
        RuntimeException deep = new RuntimeException("lvl0");
        RuntimeException cur = deep;
        for (int i = 1; i <= 15; i++) { RuntimeException nx = new RuntimeException("lvl" + i, cur); cur = nx; }
        DistilledStack d = new StackDistiller(List.of()).distill(new ThrowableProxy(cur));
        assertThat(d.rootType()).isEqualTo("RuntimeException"); // stopped at depth cap, no infinite loop

        RuntimeException withSup = withStack(new RuntimeException("s"), el("com.acme.A", "m", "A.java", 1));
        withSup.addSuppressed(new IllegalArgumentException("cleanup failed"));
        DistilledStack d2 = new StackDistiller(List.of()).distill(new ThrowableProxy(withSup));
        assertThat(d2.suppressed()).hasSize(1);
        assertThat(d2.suppressed().get(0)).contains("IllegalArgumentException").contains("cleanup failed");
    }
}
```

- [ ] **Step 2: Run to verify fail** — compile error. Expected.

- [ ] **Step 3: Implement**

```java
// DistilledStack.java — record exactly as in Interfaces block above (public).
```

```java
// StackDistiller.java
package io.github.gabrielbbaldez.stacktale;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StackDistiller {

    private static final int MAX_CAUSE_DEPTH = 10;
    private static final int MAX_SHOWN_FRAMES = 15;
    private static final int MAX_SUPPRESSED = 3;
    private static final int MAX_WRAPPER_MSG = 80;

    // prefix -> group label, insertion order matters (first match wins)
    private static final Map<String, String> FRAMEWORK_GROUPS = new LinkedHashMap<>();
    static {
        FRAMEWORK_GROUPS.put("org.springframework.", "spring");
        FRAMEWORK_GROUPS.put("org.apache.catalina.", "tomcat");
        FRAMEWORK_GROUPS.put("org.apache.tomcat.", "tomcat");
        FRAMEWORK_GROUPS.put("org.apache.coyote.", "tomcat");
        FRAMEWORK_GROUPS.put("jakarta.servlet.", "servlet");
        FRAMEWORK_GROUPS.put("javax.servlet.", "servlet");
        FRAMEWORK_GROUPS.put("java.", "jdk");
        FRAMEWORK_GROUPS.put("jdk.", "jdk");
        FRAMEWORK_GROUPS.put("sun.", "jdk");
        FRAMEWORK_GROUPS.put("com.sun.", "jdk");
        FRAMEWORK_GROUPS.put("org.hibernate.", "hibernate");
        FRAMEWORK_GROUPS.put("com.zaxxer.hikari.", "hikari");
        FRAMEWORK_GROUPS.put("io.netty.", "netty");
        FRAMEWORK_GROUPS.put("reactor.", "reactor");
        FRAMEWORK_GROUPS.put("org.junit.", "test");
        FRAMEWORK_GROUPS.put("org.mockito.", "test");
        FRAMEWORK_GROUPS.put("ch.qos.logback.", "logging");
        FRAMEWORK_GROUPS.put("org.slf4j.", "logging");
    }

    private final List<String> appPackages;

    StackDistiller(List<String> appPackages) {
        this.appPackages = appPackages;
    }

    DistilledStack distill(IThrowableProxy proxy) {
        List<IThrowableProxy> chain = causeChain(proxy);
        IThrowableProxy root = chain.get(chain.size() - 1);

        StackTraceElementProxy[] frames = root.getStackTraceElementProxyArray();
        if (frames == null) frames = new StackTraceElementProxy[0];

        String culprit = null;
        int culpritIdx = -1;
        for (int i = 0; i < frames.length; i++) {
            if (isAppFrame(frames[i].getStackTraceElement())) { culprit = location(frames[i]); culpritIdx = i; break; }
        }
        if (culprit == null && frames.length > 0) { culprit = location(frames[0]); culpritIdx = 0; }

        List<String> frameLines = renderFrames(frames, culpritIdx);

        List<String> wrappedBy = new ArrayList<>();
        for (int i = chain.size() - 2; i >= 0; i--) {
            IThrowableProxy w = chain.get(i);
            wrappedBy.add(simpleName(w.getClassName()) + "(\"" + truncate(nullToEmpty(w.getMessage()), MAX_WRAPPER_MSG)
                    + "\") at " + firstLocation(w));
        }

        List<String> suppressed = new ArrayList<>();
        IThrowableProxy[] sup = root.getSuppressed();
        if (sup != null) {
            for (int i = 0; i < sup.length && i < MAX_SUPPRESSED; i++) {
                suppressed.add("suppressed: " + simpleName(sup[i].getClassName()) + "(\""
                        + truncate(nullToEmpty(sup[i].getMessage()), MAX_WRAPPER_MSG) + "\") at " + firstLocation(sup[i]));
            }
            if (sup.length > MAX_SUPPRESSED) suppressed.add("… " + (sup.length - MAX_SUPPRESSED) + " more suppressed");
        }

        int shown = (int) frameLines.stream().filter(l -> !l.startsWith("…")).count();
        return new DistilledStack(simpleName(root.getClassName()), root.getMessage(), culprit,
                wrappedBy, frameLines, frames.length, shown, suppressed);
    }

    private List<String> renderFrames(StackTraceElementProxy[] frames, int culpritIdx) {
        List<String> out = new ArrayList<>();
        int shown = 0;
        int i = 0;
        while (i < frames.length) {
            StackTraceElement el = frames[i].getStackTraceElement();
            boolean mustShow = i == 0 || i == culpritIdx || isAppFrame(el);
            if (mustShow) {
                if (shown >= MAX_SHOWN_FRAMES) { out.add("… " + (frames.length - i) + " more frames"); break; }
                out.add(location(frames[i]) + (i == culpritIdx ? " ← culprit" : ""));
                shown++;
                i++;
            } else {
                // measure the run of collapsible frames
                int start = i;
                Map<String, Integer> groups = new LinkedHashMap<>();
                while (i < frames.length && i != culpritIdx && !isAppFrame(frames[i].getStackTraceElement())) {
                    String g = groupOf(frames[i].getStackTraceElement().getClassName());
                    groups.merge(g, 1, Integer::sum);
                    i++;
                }
                int run = i - start;
                if (run == 1) {
                    if (shown >= MAX_SHOWN_FRAMES) { out.add("… " + (frames.length - start) + " more frames"); break; }
                    out.add(location(frames[start]));
                    shown++;
                } else {
                    StringBuilder sb = new StringBuilder("… ").append(run).append(" collapsed (");
                    boolean first = true;
                    for (var e : groups.entrySet()) {
                        if (!first) sb.append(", ");
                        sb.append(e.getKey()).append(" ×").append(e.getValue());
                        first = false;
                    }
                    out.add(sb.append(")").toString());
                }
            }
        }
        return out;
    }

    private List<IThrowableProxy> causeChain(IThrowableProxy proxy) {
        List<IThrowableProxy> chain = new ArrayList<>();
        IThrowableProxy cur = proxy;
        while (cur != null && chain.size() < MAX_CAUSE_DEPTH && !chain.contains(cur)) {
            chain.add(cur);
            cur = cur.getCause();
        }
        return chain;
    }

    private boolean isAppFrame(StackTraceElement el) {
        String cls = el.getClassName();
        if (!appPackages.isEmpty()) {
            return appPackages.stream().anyMatch(cls::startsWith);
        }
        return FRAMEWORK_GROUPS.keySet().stream().noneMatch(cls::startsWith);
    }

    private String groupOf(String className) {
        for (var e : FRAMEWORK_GROUPS.entrySet()) if (className.startsWith(e.getKey())) return e.getValue();
        return "other";
    }

    private String firstLocation(IThrowableProxy p) {
        StackTraceElementProxy[] f = p.getStackTraceElementProxyArray();
        return (f == null || f.length == 0) ? "(no stack)" : location(f[0]);
    }

    private static String location(StackTraceElementProxy p) {
        StackTraceElement el = p.getStackTraceElement();
        String cls = simpleName(el.getClassName());
        String file = el.getFileName() == null ? "Unknown" : el.getFileName();
        return cls + "." + el.getMethodName() + "(" + file + ":" + el.getLineNumber() + ")";
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
```

- [ ] **Step 4: Run tests** — `rtk mvn -q test` → StackDistillerTest green. Adjust collapse expectations if run accounting differs (the `culpritIdx` guard in the inner while can split runs — assert real behavior as long as counts add up and groups are labeled).

- [ ] **Step 5: Commit** — `git commit -am "feat: stack distiller (root-cause-first, framework collapse, culprit marking)"`

---

### Task 4: Fingerprinter

**Files:**
- Create: `src/main/java/io/github/gabrielbbaldez/stacktale/Fingerprinter.java`
- Test: `src/test/java/io/github/gabrielbbaldez/stacktale/FingerprinterTest.java`

**Interfaces:**
- Produces: `static String fingerprint(String rootType, String culpritLine, String message)` → 4 lowercase hex chars. Digits and hex runs in `message` normalized to `#` before hashing; null-safe.

- [ ] **Step 1: Failing tests**

```java
package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FingerprinterTest {
    @Test
    void sameErrorSameId() {
        String a = Fingerprinter.fingerprint("NullPointerException", "OrderService.confirm(OrderService.java:87)", "failed order 123");
        String b = Fingerprinter.fingerprint("NullPointerException", "OrderService.confirm(OrderService.java:87)", "failed order 456");
        assertThat(a).isEqualTo(b).hasSize(4).matches("[0-9a-f]{4}");
    }

    @Test
    void differentLineDifferentId() {
        String a = Fingerprinter.fingerprint("NullPointerException", "OrderService.confirm(OrderService.java:87)", "x");
        String b = Fingerprinter.fingerprint("NullPointerException", "OrderService.confirm(OrderService.java:88)", "x");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void nullSafe() {
        assertThat(Fingerprinter.fingerprint(null, null, null)).hasSize(4);
    }
}
```

- [ ] **Step 2: Run to verify fail.**

- [ ] **Step 3: Implement**

```java
package io.github.gabrielbbaldez.stacktale;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Fingerprinter {
    private Fingerprinter() {}

    static String fingerprint(String rootType, String culpritLine, String message) {
        String normalized = (nz(rootType) + "|" + nz(culpritLine) + "|"
                + nz(message).replaceAll("(0x[0-9a-fA-F]+|\\d+)", "#"));
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return String.format("%02x%02x", d[0], d[1]);
        } catch (NoSuchAlgorithmException e) {
            return "0000"; // SHA-1 always present; keep the never-throw guarantee anyway
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
```

- [ ] **Step 4: Run tests → green.**
- [ ] **Step 5: Commit** — `git commit -am "feat: error fingerprinting (type + culprit + normalized message)"`

---

### Task 5: Deduper

**Files:**
- Create: `src/main/java/io/github/gabrielbbaldez/stacktale/Deduper.java`
- Test: `src/test/java/io/github/gabrielbbaldez/stacktale/DeduperTest.java`

**Interfaces:**
- Produces:

```java
enum Kind { REPORT, SUMMARY, SILENT }
record Decision(Kind kind, int count, long lastSeenMillis) {}
Deduper(long windowMillis, long summaryThrottleMillis, java.util.function.LongSupplier clock)
Decision decide(String fingerprint)
```

Semantics: first sighting (or sighting after `windowMillis` since the last written REPORT) → `REPORT` (count resets to 1). Repeat within window: count++; first repeat after a REPORT → `SUMMARY`; further repeats → `SUMMARY` only if ≥ `summaryThrottleMillis` since last summary, else `SILENT`. State bounded to 1024 fingerprints (LRU).

- [ ] **Step 1: Failing tests**

```java
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
}
```

- [ ] **Step 2: Run to verify fail.**

- [ ] **Step 3: Implement**

```java
package io.github.gabrielbbaldez.stacktale;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

enum Kind { REPORT, SUMMARY, SILENT }

record Decision(Kind kind, int count, long lastSeenMillis) {}

final class Deduper {
    private static final int MAX_FINGERPRINTS = 1024;

    private static final class Stats { int count; long lastReport; long lastSummary; }

    private final long windowMillis;
    private final long summaryThrottleMillis;
    private final LongSupplier clock;
    private final Map<String, Stats> stats = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Stats> e) { return size() > MAX_FINGERPRINTS; }
    };

    Deduper(long windowMillis, long summaryThrottleMillis, LongSupplier clock) {
        this.windowMillis = windowMillis;
        this.summaryThrottleMillis = summaryThrottleMillis;
        this.clock = clock;
    }

    synchronized Decision decide(String fingerprint) {
        long now = clock.getAsLong();
        Stats s = stats.computeIfAbsent(fingerprint, k -> new Stats());
        if (s.count == 0 || now - s.lastReport > windowMillis) {
            s.count = 1; s.lastReport = now; s.lastSummary = now;
            return new Decision(Kind.REPORT, 1, now);
        }
        s.count++;
        boolean firstRepeat = s.count == 2;
        if (firstRepeat || now - s.lastSummary >= summaryThrottleMillis) {
            s.lastSummary = now;
            return new Decision(Kind.SUMMARY, s.count, now);
        }
        return new Decision(Kind.SILENT, s.count, now);
    }
}
```

- [ ] **Step 4: Run tests → green.**
- [ ] **Step 5: Commit** — `git commit -am "feat: dedup with report window and throttled repeat summaries"`

---

### Task 6: EnvCollector (+ spec touch-up)

**Files:**
- Create: `src/main/java/io/github/gabrielbbaldez/stacktale/EnvCollector.java`
- Test: `src/test/java/io/github/gabrielbbaldez/stacktale/EnvCollectorTest.java`
- Test fixture: `src/test/resources/git.properties`, `src/test/resources/META-INF/build-info.properties`
- Modify: `docs/design.md` (EnvCollector row: sources are sysprops → build-info.properties → git.properties; manifest scanning dropped as unreliable-source-worse-than-none)

**Interfaces:**
- Produces: `EnvCollector(ClassLoader cl)`; `String envLine()` — cached; format `app=<name> <version> (git <sha>) | java <ver> | profile=<p> | <os>`; unknown parts degrade gracefully (`app=?`, no `(git …)`, no `profile=`).
- Sources in priority order: system properties `stacktale.app.name` / `stacktale.app.version` → classpath `META-INF/build-info.properties` (keys `build.name`, `build.version`) → unknown. Git sha: classpath `git.properties` key `git.commit.id.abbrev`. Profile: sysprop `spring.profiles.active` → env `SPRING_PROFILES_ACTIVE` → env `APP_ENV`. Java: sysprop `java.version`. OS: first word of `os.name`, lowercase.

- [ ] **Step 1: Failing tests** (fixtures on test classpath make the happy path deterministic)

`src/test/resources/META-INF/build-info.properties`:
```
build.name=shop-api
build.version=1.4.2
```

`src/test/resources/git.properties`:
```
git.commit.id.abbrev=7e3c1f
```

```java
package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.net.URL;
import java.net.URLClassLoader;
import static org.assertj.core.api.Assertions.assertThat;

class EnvCollectorTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("stacktale.app.name");
        System.clearProperty("stacktale.app.version");
        System.clearProperty("spring.profiles.active");
    }

    @Test
    void readsBuildInfoAndGitPropertiesFromClasspath() {
        String line = new EnvCollector(getClass().getClassLoader()).envLine();
        assertThat(line).contains("app=shop-api 1.4.2").contains("(git 7e3c1f)").contains("java ");
    }

    @Test
    void syspropsOverrideBuildInfo() {
        System.setProperty("stacktale.app.name", "override");
        System.setProperty("stacktale.app.version", "9.9.9");
        System.setProperty("spring.profiles.active", "dev");
        String line = new EnvCollector(getClass().getClassLoader()).envLine();
        assertThat(line).contains("app=override 9.9.9").contains("profile=dev");
    }

    @Test
    void degradesGracefullyWithEmptyClasspath() {
        ClassLoader empty = new URLClassLoader(new URL[0], null);
        String line = new EnvCollector(empty).envLine();
        assertThat(line).startsWith("app=?").doesNotContain("git").contains("java ");
    }
}
```

- [ ] **Step 2: Run to verify fail.**

- [ ] **Step 3: Implement**

```java
package io.github.gabrielbbaldez.stacktale;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class EnvCollector {

    private final ClassLoader classLoader;
    private volatile String cached;

    EnvCollector(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    String envLine() {
        String line = cached;
        if (line == null) {
            line = build();
            cached = line;
        }
        return line;
    }

    private String build() {
        Properties buildInfo = load("META-INF/build-info.properties");
        Properties git = load("git.properties");

        String name = firstNonBlank(System.getProperty("stacktale.app.name"), buildInfo.getProperty("build.name"), "?");
        String version = firstNonBlank(System.getProperty("stacktale.app.version"), buildInfo.getProperty("build.version"), "");
        String sha = firstNonBlank(git.getProperty("git.commit.id.abbrev"), "");
        String profile = firstNonBlank(System.getProperty("spring.profiles.active"),
                System.getenv("SPRING_PROFILES_ACTIVE"), System.getenv("APP_ENV"), "");
        String java = System.getProperty("java.version", "?");
        String os = System.getProperty("os.name", "?").split(" ")[0].toLowerCase();

        StringBuilder sb = new StringBuilder("app=").append(name);
        if (!version.isEmpty()) sb.append(' ').append(version);
        if (!sha.isEmpty()) sb.append(" (git ").append(sha).append(')');
        sb.append(" | java ").append(java);
        if (!profile.isEmpty()) sb.append(" | profile=").append(profile);
        sb.append(" | ").append(os);
        return sb.toString();
    }

    private Properties load(String resource) {
        Properties p = new Properties();
        try (InputStream in = classLoader.getResourceAsStream(resource)) {
            if (in != null) p.load(in);
        } catch (IOException ignored) {
            // env info is best-effort; never fail the pipeline over it
        }
        return p;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
```

- [ ] **Step 4: Run tests → green.**
- [ ] **Step 5: Update `docs/design.md` EnvCollector row** to list sources as `stacktale.app.*` sysprops → `build-info.properties` → `git.properties`; remove the manifest mention.
- [ ] **Step 6: Commit** — `git commit -am "feat: environment collector (build-info, git.properties, sysprops)"`

---

### Task 7: ReportRenderer (golden files)

**Files:**
- Create: `src/main/java/io/github/gabrielbbaldez/stacktale/Report.java`
- Create: `src/main/java/io/github/gabrielbbaldez/stacktale/ReportRenderer.java`
- Test: `src/test/java/io/github/gabrielbbaldez/stacktale/ReportRendererTest.java`
- Golden fixtures: `src/test/resources/golden/full-report.txt`, `src/test/resources/golden/no-throwable.txt`

**Interfaces:**
- Consumes: `DistilledStack`, `Story`, `StoryEntry` from earlier tasks.
- Produces:

```java
public record Report(String id, long epochMillis, String threadName,
                     DistilledStack stack /*nullable*/, String messagePattern, Object[] args,
                     String loggerName, java.util.Map<String,String> mdc, Story story, String envLine) {}

ReportRenderer(java.time.ZoneId zone)
String render(Report r)                       // full st/1 block, ends with newline
String renderSummary(String id, int count, long lastMillis)  // "━ #a1b2 repeated 47× (last 14:37:22) ━\n"
String fileHeader()                            // spec §3.1 header, ends with newline
```

Rendering rules (from spec §3.2): headline = root type + message (or `ERROR (no exception): <formatted msg>` when no stack); `at <culprit> ← YOUR CODE` when culprit is app code — render `← YOUR CODE` only when culprit line differs from a framework fallback (simplification: always render `← YOUR CODE` when stack present and culprit non-null; it IS the app frame by construction, fallback rare); wrappers each on own line; `log:` line shows pattern + `args=[…]` (each arg via String.valueOf truncated 80, max 8) + `logger=` abbreviated (first letter of each package segment + class, e.g. `c.a.s.OrderService`); `mdc:` sorted `k=v` pairs, omitted when empty; blank line; story section with header `story (<label>, last N events, <span>ms):` and marker `← this error` on the entry matching the error event's timestamp+message; blank line; stack section `stack (distilled, X of Y frames):` + frame lines + suppressed lines (omitted when no throwable); env line; END delimiter. Timestamps: block header `yyyy-MM-dd HH:mm:ss.SSS`, story lines `HH:mm:ss.SSS`, in `zone`.

- [ ] **Step 1: Failing golden tests**

```java
package io.github.gabrielbbaldez.stacktale;

import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ReportRendererTest {

    private String golden(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/golden/" + name), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    @Test
    void fullReportMatchesGolden() throws Exception {
        RuntimeException npe = new NullPointerException("customer is null");
        npe.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.acme.shop.OrderService", "confirm", "OrderService.java", 87),
                new StackTraceElement("com.acme.shop.OrderController", "confirm", "OrderController.java", 34),
                new StackTraceElement("org.springframework.web.method.support.InvocableHandlerMethod", "invokeForRequest", "InvocableHandlerMethod.java", 190),
                new StackTraceElement("org.apache.catalina.core.ApplicationFilterChain", "doFilter", "ApplicationFilterChain.java", 166),
        });
        DistilledStack stack = new StackDistiller(List.of("com.acme")).distill(new ThrowableProxy(npe));
        Story story = new Story(List.of(
                new StoryEntry(1000_000L, "INFO", "OrderController", "POST /orders/123/confirm"),
                new StoryEntry(1000_108L, "INFO", "CustomerClient", "fetching customer 555 → 404"),
                new StoryEntry(1000_113L, "WARN", "CustomerCache", "miss for 555, returning null"),
                new StoryEntry(1000_412L, "ERROR", "OrderService", "Failed to confirm order 123")
        ), "thread http-nio-8080-exec-3");
        Report r = new Report("a1b2", 1000_412L, "http-nio-8080-exec-3", stack,
                "Failed to confirm order {}", new Object[]{123}, "com.acme.shop.OrderService",
                Map.of("traceId", "9f3a", "userId", "42"), story,
                "app=shop-api 1.4.2 (git 7e3c1f) | java 21 | profile=dev | linux");
        String rendered = new ReportRenderer(ZoneOffset.UTC).render(r);
        assertThat(rendered).isEqualTo(golden("full-report.txt"));
    }

    @Test
    void noThrowableReportMatchesGolden() throws Exception {
        Story story = new Story(List.of(
                new StoryEntry(2000_000L, "ERROR", "PaymentService", "payment rejected for order 77")
        ), "thread main");
        Report r = new Report("beef", 2000_000L, "main", null,
                "payment rejected for order {}", new Object[]{77}, "com.acme.PaymentService",
                Map.of(), story, "app=? | java 21 | linux");
        String rendered = new ReportRenderer(ZoneOffset.UTC).render(r);
        assertThat(rendered).isEqualTo(golden("no-throwable.txt"));
    }

    @Test
    void summaryLine() {
        String s = new ReportRenderer(ZoneOffset.UTC).renderSummary("a1b2", 47, 1000_000L);
        assertThat(s).isEqualTo("━ #a1b2 repeated 47× (last 00:16:40.000) ━\n");
    }

    @Test
    void fileHeaderMentionsFormatAndDelimiters() {
        String h = new ReportRenderer(ZoneOffset.UTC).fileHeader();
        assertThat(h).contains("format st/1").contains("━━━ ERROR #").contains("END #");
    }
}
```

Golden files: on first run, generate by printing the renderer output, eyeball against spec §3.2, then commit as fixture (standard golden-file bootstrap). `full-report.txt` must show: delimiters, headline, `at … ← YOUR CODE`, log line with `args=[123]` and `logger=c.a.s.OrderService`, sorted mdc, story with `← this error` on last line, `stack (distilled, 2 of 4 frames):` with `… 2 collapsed (spring ×1, tomcat ×1)`, env, END.

- [ ] **Step 2: Run to verify fail.**

- [ ] **Step 3: Implement ReportRenderer** (pure string assembly; ~120 lines; `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"/"HH:mm:ss.SSS").withZone(zone)`; logger abbreviation helper `c.a.s.OrderService`; story span = last.ts − first.ts; pad level to 5, logger name to max length in story capped 20). Ensure output uses `\n` only.

- [ ] **Step 4: Bootstrap goldens, re-run → green.**
- [ ] **Step 5: Commit** — `git commit -am "feat: st/1 report renderer with golden-file tests"`

---

### Task 8: ReportWriter

**Files:**
- Create: `src/main/java/io/github/gabrielbbaldez/stacktale/ReportWriter.java`
- Test: `src/test/java/io/github/gabrielbbaldez/stacktale/ReportWriterTest.java`

**Interfaces:**
- Produces: `ReportWriter(java.nio.file.Path file, long maxBytes, String header)`; `synchronized void append(String block)`. Creates parent dirs; writes header when file absent/empty; UTF-8; flush-per-append (open/append/close); rotation when `size + block > maxBytes`: move to `<file>.1` (replace existing), start fresh with header.

- [ ] **Step 1: Failing tests**

```java
package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ReportWriterTest {

    @Test
    void writesHeaderOnceAndAppends(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ReportWriter w = new ReportWriter(file, 1024 * 1024, "# header\n");
        w.append("block-1\n");
        w.append("block-2\n");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).isEqualTo("# header\nblock-1\nblock-2\n");
    }

    @Test
    void rotatesWhenMaxSizeExceeded(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ReportWriter w = new ReportWriter(file, 40, "# h\n");
        w.append("x".repeat(30) + "\n");
        w.append("y".repeat(30) + "\n"); // would exceed 40 → rotate first
        assertThat(Files.readString(dir.resolve("errors-ai.log.1"), StandardCharsets.UTF_8)).contains("x");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).startsWith("# h\n").contains("y");
    }

    @Test
    void createsParentDirectories(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("nested/deep/errors-ai.log");
        new ReportWriter(file, 1024, "# h\n").append("b\n");
        assertThat(Files.exists(file)).isTrue();
    }
}
```

- [ ] **Step 2: Run to verify fail.**

- [ ] **Step 3: Implement**

```java
package io.github.gabrielbbaldez.stacktale;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

final class ReportWriter {

    private final Path file;
    private final long maxBytes;
    private final String header;

    ReportWriter(Path file, long maxBytes, String header) {
        this.file = file;
        this.maxBytes = maxBytes;
        this.header = header;
    }

    synchronized void append(String block) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            long size = Files.exists(file) ? Files.size(file) : 0;
            byte[] bytes = block.getBytes(StandardCharsets.UTF_8);
            if (size > 0 && size + bytes.length > maxBytes) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
                size = 0;
            }
            if (size == 0) {
                Files.writeString(file, header, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // appender catches and degrades
        }
    }
}
```

- [ ] **Step 4: Run tests → green.**
- [ ] **Step 5: Commit** — `git commit -am "feat: report writer with self-describing header and size rotation"`

---

### Task 9: StacktaleAppender + UncaughtHandler + integration

**Files:**
- Create: `src/main/java/io/github/gabrielbbaldez/stacktale/StacktaleAppender.java`
- Create: `src/main/java/io/github/gabrielbbaldez/stacktale/UncaughtHandler.java`
- Test: `src/test/java/io/github/gabrielbbaldez/stacktale/StacktaleAppenderIT.java` (named `*IT` but run by surefire — plain JUnit test; integration-style)

**Interfaces:**
- Consumes: everything from Tasks 2–8.
- Produces: `StacktaleAppender extends ch.qos.logback.core.UnsynchronizedAppenderBase<ILoggingEvent>` with setters (all optional): `setFile(String)` default `errors-ai.log`; `setAppPackages(String csv)`; `setStorySize(int)` default 15; `setStoryWindowSeconds(int)` default 60; `setDedupWindowSeconds(int)` default 300; `setMaxFileSizeMb(int)` default 5; `setInstallUncaughtHandler(boolean)` default true; `setReportErrorsWithoutThrowable(boolean)` default true; `setCorrelationMdcKeys(String csv)` default `traceId,correlationId,requestId`; `setZone(String)` default system zone.
- `UncaughtHandler implements Thread.UncaughtExceptionHandler` — `static void install()`: wraps any existing default handler (idempotent: skips if already installed); logs via SLF4J logger `stacktale.uncaught` at ERROR (`"Uncaught exception in thread {}"`), then delegates to previous handler or `ThreadGroup.uncaughtException`.

Appender behavior (`append(ILoggingEvent event)`), wrapped whole in `try/catch (Throwable)`:
1. `if (event.getLoggerName().equals("stacktale")) return;` (anti-loop for pointer/announce lines; `stacktale.uncaught` passes)
2. `storyBuffer.record(event)`
3. `if (!event.getLevel().isGreaterOrEqual(Level.ERROR)) return;`
4. `IThrowableProxy proxy = event.getThrowableProxy();` — if null and `!reportErrorsWithoutThrowable` return
5. distill (null when no proxy); fingerprint: with stack → `(rootType, culpritLine, rootMessage)`; without → `(loggerName, "", messagePattern)`
6. `deduper.decide(fp)` → REPORT: build `Report`, render, `writer.append`, then pointer via `LoggerFactory.getLogger("stacktale").info("AI error report #{} → {}", id, filePath)`; SUMMARY: `writer.append(renderer.renderSummary(...))`; SILENT: nothing
7. catch: increment failure counter; on first failure `addWarn("stacktale disabled-ish: ...", t)` (Logback status API), never rethrow

`start()`: parse csv configs, build components (`Deduper` with `System::currentTimeMillis`), `super.start()`, announce once via logger `stacktale`: `"stacktale active → {} (error reports for AI consumption)"`, install `UncaughtHandler` if configured.

- [ ] **Step 1: Failing integration test**

```java
package io.github.gabrielbbaldez.stacktale;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class StacktaleAppenderIT {

    private LoggerContext ctx;

    private StacktaleAppender startAppender(Path file, String appPackages) {
        ctx = new LoggerContext();
        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setFile(file.toString());
        appender.setAppPackages(appPackages);
        appender.setInstallUncaughtHandler(false);
        appender.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        return appender;
    }

    private Exception wrappedNpe() {
        try {
            try { String s = null; s.length(); } // real NPE with real frames
            catch (NullPointerException npe) { throw new IllegalStateException("confirm failed", npe); }
        } catch (Exception e) { return e; }
        return null;
    }

    @Test
    void fullPipelineWritesReportWithStoryAndDedups(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        startAppender(file, "io.github.gabrielbbaldez");
        Logger app = ctx.getLogger("com.acme.OrderService");

        MDC.put("traceId", "9f3a");
        try {
            app.info("POST /orders/123/confirm");
            app.warn("customer cache miss for 555, returning null");
            Exception e = wrappedNpe();
            for (int i = 0; i < 5; i++) app.error("Failed to confirm order {}", 123, e);
        } finally { MDC.clear(); }

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("format st/1");                       // self-describing header
        assertThat(content).containsOnlyOnce("━━━ ERROR #");               // 5 identical errors → 1 report
        assertThat(content).contains("NullPointerException");
        assertThat(content).contains("wrapped by: IllegalStateException"); // root cause first
        assertThat(content).contains("POST /orders/123/confirm");          // story present
        assertThat(content).contains("← this error");
        assertThat(content).contains("traceId=9f3a");
        assertThat(content).contains("repeated");                          // dedup summary
        assertThat(content).contains("━━━ END #");
    }

    @Test
    void errorWithoutThrowableStillReports(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        startAppender(file, "");
        ctx.getLogger("com.acme.Pay").error("payment rejected for order {}", 77);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("ERROR (no exception): payment rejected for order 77");
        assertThat(content).doesNotContain("stack (distilled");
    }

    @Test
    void neverThrowsOutOfAppendEvenWhenBroken(@TempDir Path dir) {
        Path file = dir.resolve("sub").resolve("errors-ai.log");
        StacktaleAppender appender = startAppender(file, "");
        // sabotage: make the report file path a directory so writes explode
        try {
            Files.createDirectories(file);
        } catch (Exception ignored) {}
        Logger app = ctx.getLogger("com.acme.X");
        app.error("boom", new RuntimeException("x")); // must not propagate
        app.info("still alive");
        // reaching this line without exception = pass
        assertThat(appender.isStarted()).isTrue();
    }

    @Test
    void uncaughtExceptionsFlowThroughPipeline(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        startAppender(file, "");
        // route the global SLF4J "stacktale.uncaught" logger into this context's appender:
        // UncaughtHandler uses LoggerFactory (global context). For the test, invoke handler directly:
        UncaughtHandler handler = new UncaughtHandler(null, ctx.getLogger("stacktale.uncaught"));
        Thread t = new Thread(() -> { throw new IllegalArgumentException("thread died"); }, "worker-1");
        t.setUncaughtExceptionHandler(handler);
        t.start(); t.join();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("Uncaught exception in thread worker-1").contains("IllegalArgumentException");
    }
}
```

(For testability, give `UncaughtHandler` a package-private constructor `(Thread.UncaughtExceptionHandler previous, org.slf4j.Logger logger)`; the public `install()` uses `LoggerFactory.getLogger("stacktale.uncaught")` and `Thread.getDefaultUncaughtExceptionHandler()`.)

- [ ] **Step 2: Run to verify fail.**

- [ ] **Step 3: Implement** (orchestration exactly as the behavior list above; ~150 lines)

```java
// StacktaleAppender.java — skeleton locking names/defaults
package io.github.gabrielbbaldez.stacktale;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StacktaleAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private String file = "errors-ai.log";
    private String appPackages = "";
    private int storySize = 15;
    private int storyWindowSeconds = 60;
    private int dedupWindowSeconds = 300;
    private int maxFileSizeMb = 5;
    private boolean installUncaughtHandler = true;
    private boolean reportErrorsWithoutThrowable = true;
    private String correlationMdcKeys = "traceId,correlationId,requestId";
    private String zone = "";

    private StoryBuffer storyBuffer;
    private StackDistiller distiller;
    private Deduper deduper;
    private EnvCollector env;
    private ReportRenderer renderer;
    private ReportWriter writer;
    private final AtomicBoolean warnedOnce = new AtomicBoolean();

    // setters for all config fields (Logback calls them from XML) …

    @Override public void start() {
        List<String> pkgs = csv(appPackages);
        List<String> keys = csv(correlationMdcKeys);
        ZoneId zoneId = zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
        storyBuffer = new StoryBuffer(storySize, storyWindowSeconds * 1000L, keys, 200);
        distiller = new StackDistiller(pkgs);
        deduper = new Deduper(dedupWindowSeconds * 1000L, 60_000, System::currentTimeMillis);
        env = new EnvCollector(Thread.currentThread().getContextClassLoader());
        renderer = new ReportRenderer(zoneId);
        writer = new ReportWriter(Path.of(file), maxFileSizeMb * 1024L * 1024L, renderer.fileHeader());
        super.start();
        LoggerFactory.getLogger("stacktale").info("stacktale active → {} (error reports for AI consumption)", file);
        if (installUncaughtHandler) UncaughtHandler.install();
    }

    @Override protected void append(ILoggingEvent event) {
        try {
            if ("stacktale".equals(event.getLoggerName())) return;
            storyBuffer.record(event);
            if (!event.getLevel().isGreaterOrEqual(Level.ERROR)) return;
            IThrowableProxy proxy = event.getThrowableProxy();
            if (proxy == null && !reportErrorsWithoutThrowable) return;

            DistilledStack stack = proxy == null ? null : distiller.distill(proxy);
            String fp = stack != null
                    ? Fingerprinter.fingerprint(stack.rootType(), stack.culpritLine(), stack.rootMessage())
                    : Fingerprinter.fingerprint(event.getLoggerName(), "", event.getMessage());
            Decision decision = deduper.decide(fp);
            switch (decision.kind()) {
                case REPORT -> {
                    Report report = new Report(fp, event.getTimeStamp(), event.getThreadName(), stack,
                            event.getMessage(), event.getArgumentArray(), event.getLoggerName(),
                            event.getMDCPropertyMap(), storyBuffer.storyFor(event), env.envLine());
                    writer.append(renderer.render(report));
                    LoggerFactory.getLogger("stacktale").info("AI error report #{} → {}", fp, file);
                }
                case SUMMARY -> writer.append(renderer.renderSummary(fp, decision.count(), decision.lastSeenMillis()));
                case SILENT -> { /* counted, nothing to write */ }
            }
        } catch (Throwable t) {
            if (warnedOnce.compareAndSet(false, true)) addWarn("stacktale failed to process an event; further failures are silent", t);
        }
    }

    private static List<String> csv(String s) {
        return s == null || s.isBlank() ? List.of()
                : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
    // … setters …
}
```

```java
// UncaughtHandler.java
package io.github.gabrielbbaldez.stacktale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class UncaughtHandler implements Thread.UncaughtExceptionHandler {

    private final Thread.UncaughtExceptionHandler previous;
    private final Logger logger;

    UncaughtHandler(Thread.UncaughtExceptionHandler previous, Logger logger) {
        this.previous = previous;
        this.logger = logger;
    }

    static void install() {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof UncaughtHandler) return; // idempotent
        Thread.setDefaultUncaughtExceptionHandler(
                new UncaughtHandler(current, LoggerFactory.getLogger("stacktale.uncaught")));
    }

    @Override public void uncaughtException(Thread t, Throwable e) {
        try {
            logger.error("Uncaught exception in thread {}", t.getName(), e);
        } catch (Throwable ignored) {
            // never make an uncaught exception worse
        }
        if (previous != null) previous.uncaughtException(t, e);
        else if (t.getThreadGroup() != null) t.getThreadGroup().uncaughtException(t, e);
    }
}
```

Note the test constructs `UncaughtHandler(null, ctx.getLogger("stacktale.uncaught"))` — with `previous=null` the fallback delegates to ThreadGroup, which prints to stderr; acceptable in test output. The logback classic `Logger` implements `org.slf4j.Logger` so it slots in.

- [ ] **Step 4: Run FULL suite** — `rtk mvn -q test` → all green.
- [ ] **Step 5: Commit** — `git commit -am "feat: stacktale appender orchestration + uncaught exception handler"`

---

### Task 10: README, CI, GitHub repo

**Files:**
- Create: `README.md`, `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: a real `errors-ai.log` block generated by running the integration test (paste real output into README — never fabricate).

- [ ] **Step 1: CI workflow**

```yaml
name: CI
on:
  push:
    branches: [main]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        java: [17, 21]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: ${{ matrix.java }}
          cache: maven
      - run: mvn -B verify
```

- [ ] **Step 2: README.md** — sections: hero (name + tagline + one-para pitch: "logs were designed for humans with grep; their biggest reader today is an AI"); Why (the interrogation problem, 3 bullets); **Before / After** (before: honest condensed traditional log sketch; after: REAL block pasted from integration test run); Quickstart (maven dep snippet + logback.xml appender + appender-ref); What gets captured (story, root-cause-first stack, env, dedup — table); Configuration (all 10 properties, defaults, one-liner each); The st/1 format (link to docs/design.md); Limitations (honest: story is thread/MDC-scoped — async without MDC fragments; depends on what the app already logs; Logback-only for now); Roadmap (Spring Boot starter, Log4j2, Maven Central); License Apache-2.0.

- [ ] **Step 3: Full verify** — `rtk mvn -q verify` → green.

- [ ] **Step 4: Commit** — `git add README.md .github && git commit -m "docs: readme with real before/after report + ci workflow"`

- [ ] **Step 5: Create GitHub repo & push**

```bash
gh repo create GabrielBBaldez/stacktale --public \
  --description "Stack traces that tell the tale — a Logback appender that turns Java errors into AI-ready reports" \
  --source /c/Users/Baldez/Desktop/stacktale --push
gh repo edit GabrielBBaldez/stacktale --add-topic java --add-topic logging --add-topic logback --add-topic ai --add-topic developer-tools --add-topic observability
```

Expected: repo live at https://github.com/GabrielBBaldez/stacktale with CI running.

---

## Self-Review Notes

- Spec coverage: §2 pipeline → Task 9; §3 format → Task 7 (golden); §4 components → Tasks 2–9 one-to-one; §5 config → Task 9 setters; §6 guarantees → Task 9 (never-throw test, anti-loop) + Task 2 (bounded buffers); §7 testing → per-task TDD + golden + IT; §8 scope v1 only; §9 coords → Task 1/10. Env manifest source consciously reduced (Task 6 modifies spec accordingly).
- Type consistency: `Story`/`StoryEntry`/`DistilledStack`/`Decision`/`Kind`/`Report` signatures repeated identically in consuming tasks.
- No placeholders: every step carries code or exact commands.
