# stacktale — design document

**Date:** 2026-07-09
**Status:** approved — v1 (MVP) scope
**Tagline:** *stack traces that tell the tale*

## 1. Problem & thesis

Java error logs were designed in the 90s for a human with `grep` and a terminal. The stack
trace format is essentially unchanged since 1995. Meanwhile, the most frequent reader of an
error log today is an AI assistant — and the information it needs most is exactly what the
current format throws away:

- **What was happening before the error.** A stack trace says *where* the code died, not
  *what led to it*. The log lines that explain the failure exist, but they are interleaved
  with 20 other threads, hundreds of lines above the exception.
- **The values involved.** `NullPointerException at OrderService.java:87` forces the reader
  to guess which field was null. The message args (`orderId=123`) and MDC were right there
  at log time — and got scattered or dropped.
- **The environment.** App version, git commit, Java version, active profile: an AI asks
  for these in half of all debugging conversations because no log line carries them.
- **Signal.** 41-frame stacks where 39 frames are Spring/Tomcat plumbing; the same
  exception dumped 400 times in a loop.

When a developer pastes such a log into an AI, the session becomes an interrogation:
5–10 messages of the AI asking for context that existed at the moment of the error and was
thrown away. stacktale's thesis: **capture that context at the source and emit it as a
single, structured, token-efficient report designed for an AI reader** — while the human
log stays exactly as it is.

Post-processing (cleaning an existing log) cannot do this: by the time the log is written,
the story, the values and the environment are already gone. This must live inside the
logging pipeline.

## 2. What stacktale does

stacktale is a Logback appender. You add one dependency and one `appender-ref` to
`logback.xml`. From then on:

- Every log event feeds a small in-memory **story buffer** (cheap, bounded).
- When an **ERROR** event passes through, stacktale assembles a complete **error report**
  — root cause, culprit frame, log message + args, MDC, the story of what happened before,
  a distilled stack, and environment info — and appends it to a separate file,
  **`errors-ai.log`**, in a delimited, self-describing, token-efficient format.
- The human console/file logs are untouched. A single pointer line is emitted through a
  `stacktale` logger: `AI error report #a1b2 → ./errors-ai.log`.
- Repeated errors are deduplicated: one report + a repeat counter, not 400 dumps.

The file is the interface. An AI coding assistant (Claude Code, Cursor, etc.) reads
`errors-ai.log` directly — no copy-pasting, no grepping through interleaved noise.

## 3. The report format (public API)

The report format is a **public, versioned API** (`st/1`). Golden-file tests pin it.
Design goals: delimited (easy to locate), self-describing (an AI understands it with zero
docs), token-efficient (no JSON punctuation tax, no repeated keys), stable.

### 3.1 File header (written once per file)

```
# errors-ai.log — AI-oriented error reports (format st/1, https://github.com/GabrielBBaldez/stacktale)
# Each report is delimited by "━━━ ERROR #<id> ━━━" ... "━━━ END #<id> ━━━".
# Sections: headline, at, log, mdc, story (events leading up to and including the error, oldest first),
# stack (distilled; framework frames collapsed), env. "← YOUR CODE" marks app frames.
# env: app=<name> <version> (git <sha>) | java <ver> | profile=<active> | <os>
```

### 3.2 Report block

```
━━━ ERROR #a1b2 ━━━ 2026-07-09 14:32:05.114 thread=http-nio-8080-exec-3 ━━━
NullPointerException: Cannot invoke "Customer.getEmail()" because "customer" is null
at OrderService.confirm(OrderService.java:87) ← YOUR CODE
wrapped by: OrderException("confirm failed") at OrderService.confirm(OrderService.java:92)
log: "Failed to confirm order {}" args=[123] logger=c.a.s.OrderService
mdc: traceId=9f3a userId=42

story (thread http-nio-8080-exec-3, last 4 events, 412ms):
  14:32:04.702 INFO  OrderController  POST /orders/123/confirm
  14:32:04.810 INFO  CustomerClient   fetching customer 555 → 404
  14:32:04.815 WARN  CustomerCache    miss for 555, returning null
  14:32:05.114 ERROR OrderService     Failed to confirm order 123   ← this error

stack (distilled, 2 of 41 frames):
  OrderService.confirm(OrderService.java:87) ← culprit
  OrderController.confirm(OrderController.java:34)
  … 39 collapsed (spring ×24, tomcat ×11, jdk ×4)

env: app=shop-api 1.4.2 (git 7e3c1f) | java 21 | profile=dev | linux
━━━ END #a1b2 ━━━
```

Format decisions:

- **Root cause first.** Java prints wrapper exceptions first and the root cause last
  ("Caused by"). stacktale inverts: the headline IS the root cause; wrappers are one
  `wrapped by:` line each. The AI reads the most important fact first.
- **Culprit frame** = first app-code frame of the root cause (fallback: top frame).
- **story** = events leading up to and including the error (the error itself is the last
  line, marked `← this error`), oldest first, from the same correlation context (MDC trace
  key when present, otherwise same thread), within a time window.
- **Report ID** = first 8 hex chars (32 bits) of the error fingerprint. Stable across
  repeats, referenced by the console pointer line. (16 bits would collide with ~50%
  probability at ~300 distinct errors — birthday bound vs. the 1024-entry dedup map.)
- **Repeats** don't produce new blocks. A throttled summary line is appended instead:
  `━ #a1b2 repeated 47× (last 14:37:22) ━`.
- ERROR events **without** a throwable still produce a report (message + story still tell
  a tale); the stack section is omitted. Configurable.

## 4. Architecture

Single Maven module, ~8 core classes, package `io.github.gabrielbbaldez.stacktale`.
Zero dependencies beyond `logback-classic` (and `slf4j-api` transitively).

```
log event ──► StacktaleAppender.append()
                 │
                 ├─ level < ERROR ──► StoryBuffer.record()          (hot path, cheap)
                 │
                 └─ level == ERROR ─► StoryBuffer.record()
                                      Fingerprinter.fingerprint()
                                      Deduper.shouldReport()?
                                        ├─ no  ─► repeat counter (throttled summary line)
                                        └─ yes ─► ReportRenderer.render(
                                                    event, StackDistiller.distill(t),
                                                    StoryBuffer.storyFor(event),
                                                    EnvCollector.env())
                                                  ──► ReportWriter.append(report)
                                                  ──► pointer line via logger "stacktale"
```

| Component | Responsibility |
|---|---|
| `StacktaleAppender` | Logback `AppenderBase<ILoggingEvent>`; config setters; orchestration; anti-loop guard (ignores `stacktale.*` loggers); installs `UncaughtHandler` on start |
| `StoryBuffer` | Bounded ring buffers of recent events: per correlation key (MDC `traceId`/`correlationId`/`requestId`, LRU-bounded map) with per-thread fallback; entries = (ts, level, short logger, truncated message) |
| `StackDistiller` | Walks the throwable chain; classifies frames (app vs framework via configurable prefixes); finds root cause + culprit frame; collapses framework runs with per-group counts; dedups repeated caused-by frames; renders suppressed exceptions as one line each |
| `Fingerprinter` | SHA-1 → short id over (root exception type + culprit frame + message with digits normalized) |
| `Deduper` | Fingerprint → (count, lastSeen) map with time window (default 5 min); decides report vs counter; throttles summary lines (≥60s apart) |
| `EnvCollector` | Lazy, cached once: app name/version (`stacktale.app.*` sysprops, then Spring Boot `build-info.properties`), git sha (`git.properties`), java version, os, active profile (`spring.profiles.active` sysprop/env). Manifest scanning was dropped: picking the wrong jar's manifest is worse than showing `?` |
| `ReportRenderer` | Pure function: assembles the `st/1` block from parts. Golden-file tested |
| `ReportWriter` | Appends UTF-8 to `errors-ai.log` (creating it with the self-describing header); immediate flush; size-based rotation (default 5 MB, keep 1 backup); synchronized |
| `UncaughtHandler` | Optional `Thread.setDefaultUncaughtExceptionHandler` wrapper: delegates to any pre-existing handler, and logs the throwable via logger `stacktale.uncaught` so it flows through the normal pipeline. Covers plain-Java apps where exceptions die without a `log.error` |

## 5. Configuration

Everything is optional; configured as standard Logback appender properties.

```xml
<appender name="STACKTALE" class="io.github.gabrielbbaldez.stacktale.StacktaleAppender">
  <file>errors-ai.log</file>              <!-- default: ./errors-ai.log -->
  <appPackages>com.acme.shop</appPackages><!-- marks "← YOUR CODE"; default: heuristic (non-framework = app) -->
  <storySize>15</storySize>               <!-- events kept per context -->
  <storyWindowSeconds>60</storyWindowSeconds>
  <dedupWindowSeconds>300</dedupWindowSeconds>
  <maxFileSizeMb>5</maxFileSizeMb>
  <installUncaughtHandler>true</installUncaughtHandler>
  <reportErrorsWithoutThrowable>true</reportErrorsWithoutThrowable>
  <correlationMdcKeys>traceId,correlationId,requestId</correlationMdcKeys>
</appender>
<root level="INFO">
  <appender-ref ref="CONSOLE"/>
  <appender-ref ref="STACKTALE"/>
</root>
```

On start, stacktale announces itself once (via logger `stacktale`):
`stacktale active → ./errors-ai.log (error reports for AI consumption)`.

## 6. Guarantees

1. **Never break the host app.** Any internal exception is caught, counted, and (at most
   once) logged as a single WARN. A broken stacktale degrades to a no-op appender.
2. **Cheap happy path.** Non-error events cost one bounded-buffer insert (message
   formatting + truncation). No I/O, no allocation beyond the entry. Error path may cost
   a few ms (render + synchronous write) — errors are rare; acceptable for the dev-local
   MVP. Async writing is a documented future option.
3. **No cross-thread magic.** Story correlation follows MDC keys when present, otherwise
   same-thread. Async/reactive apps without MDC propagation get a fragmented story —
   documented limitation, not silently wrong data.
4. **Same trust boundary as existing logs.** stacktale writes what the app already logs
   (plus manifest/env metadata) to a file next to the existing logs. No network, no
   phone-home, nothing leaves the machine.
5. **File order ≈ report order, not guaranteed.** Under a concurrent burst of the same
   error, a cheap `repeated N×` summary can win the writer lock before the full report
   block it refers to. No data is lost; embedded timestamps give the true order. Accepted
   trade-off to keep the error path lock-light.

## 7. Testing strategy

- **Unit** (pure, fast): `StackDistiller` on synthetic exception chains (wrapped, deep,
  suppressed, recursive causes); `Fingerprinter` stability; `Deduper` windows/throttling;
  `StoryBuffer` bounds + correlation + basic concurrency; `EnvCollector` fallbacks.
- **Golden files:** `ReportRenderer` output pinned against `st/1` fixtures — the format is
  API; a diff means a deliberate format version bump.
- **Integration:** programmatic Logback setup + a small demo app that fails in
  representative ways (NPE through layers, wrapped exceptions, error loops, uncaught
  thread death); asserts on the produced `errors-ai.log`.
- CI: GitHub Actions, `mvn -B verify` on JDK 17 and 21.

## 8. Scope

**v1 (MVP)** — everything above: Logback appender, story buffer, distiller, dedup, env,
`st/1` renderer + writer, uncaught handler, self-describing file, startup announcement.
Java 17+, works day-1 in any Logback app — which includes Spring Boot (Logback is its
default), no starter needed.

**Phase 2** — `stacktale-spring-boot-starter`: auto-config, HTTP request line in the story
(method/path/status via filter), MDC trace auto-detection for Micrometer/OTel ids.
Log4j2 appender. Maven Central publication.

**Non-goals (for now)** — javaagent local-variable capture, reactive-stream story
stitching, log-aggregator integrations (Loki/ELK), redaction/PII filtering, Windows-1252
consoles. Explicitly out until v1 proves the format.

## 9. Coordinates & licensing

- GitHub: `GabrielBBaldez/stacktale` (public)
- Maven: `io.github.gabrielbbaldez:stacktale` (Central publication in Phase 2)
- Package: `io.github.gabrielbbaldez.stacktale`
- License: Apache-2.0
- Baseline: Java 17, Logback 1.5.x, JUnit 5 + AssertJ for tests
