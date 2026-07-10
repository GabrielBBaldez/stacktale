<p align="center">
  <img src="docs/banner.jpg" alt="stacktale — stack traces that tell the tale" width="720">
</p>

<p align="center">
  <a href="https://github.com/GabrielBBaldez/stacktale/actions/workflows/ci.yml"><img src="https://github.com/GabrielBBaldez/stacktale/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Java-17%2B-orange" alt="Java 17+">
  <img src="https://img.shields.io/badge/License-Apache--2.0-blue" alt="Apache-2.0">
</p>

# stacktale

> *Stack traces that tell the tale.*

A Logback appender that turns Java errors into **AI-ready reports**. Add one dependency —
and every error your app logs becomes a complete, token-efficient report in
`errors-ai.log`, designed for the reader that actually debugs your code in 2026: an AI
assistant. Your human logs stay exactly as they are.

## Why

The Java error log format was designed in the 90s for a human with `grep`. Today the most
frequent reader of an error log is an AI coding assistant — and the information it needs
most is exactly what the classic format throws away:

- **What happened before the error.** The log lines that explain the failure exist, but
  they're interleaved with 20 other threads, hundreds of lines above the stack trace.
- **The values involved.** `NullPointerException at OrderService.java:87` forces the AI
  to guess. The message args, the MDC, the state inside the exception — all captured at
  log time, all scattered or dropped.
- **The environment.** App version, git commit, Java version, profile: an AI asks for
  these in half of all debugging sessions, because no log line carries them.

So every pasted-log debugging session becomes an interrogation: 5–10 messages of the AI
asking for context that existed at the moment of the error and was thrown away.
stacktale captures that context **at the source** and writes it as one structured block.
Post-processing can't do this — by the time the log is written, the story is gone.

## What the AI sees

A real report produced by [`DemoApp`](stacktale/src/test/java/io/github/gabrielbbaldez/stacktale/DemoApp.java)
— an order flow where a cache miss returns `null`, nobody checks it, and the NPE gets
wrapped in a domain exception:

```
━━━ ERROR #c73cf755 ━━━ 2026-07-09 20:46:02.315 thread=main ━━━
NullPointerException: Cannot invoke "DemoApp$Customer.email()" because "customer" is null
at DemoApp.confirmOrder(DemoApp.java:73) ← YOUR CODE
wrapped by: OrderConfirmationException("confirmation aborted for order 123") at DemoApp.confirmOrder(DemoApp.java:76)
log: "Failed to confirm order {}" args=[123] logger=i.g.g.s.d.OrderService
mdc: traceId=9f3a userId=42
fields: failedStep=send-confirmation-email orderId=123 retryable=false

story (traceId=9f3a, last 4 events, 433ms):
  20:46:01.882 INFO  OrderController  POST /orders/123/confirm
  20:46:02.001 INFO  CustomerClient   fetching customer 555 → HTTP 404
  20:46:02.001 WARN  CustomerCache    miss for customer 555, returning null
  20:46:02.315 ERROR OrderService     Failed to confirm order 123   ← this error

stack (distilled, 2 of 2 frames):
  DemoApp.confirmOrder(DemoApp.java:73) ← culprit
  DemoApp.main(DemoApp.java:61)

env: app=shop-api 1.4.2 (git 7e3c1f) | java 21.0.6 | windows
━━━ END #c73cf755 ━━━
```

Read the `story`: the root cause — the cache returning `null` on a 404 — is right there,
one line above the error. The `fields:` line is the state the domain exception carried.
In a traditional log, the story lines were 300 lines up, tangled with other threads, and
the exception's state didn't exist at all. An AI (or you) reads this block once and knows
what happened, with which values, in which environment.

Your console meanwhile shows a single extra line:

```
INFO stacktale -- AI error report #c73cf755 → ./errors-ai.log
```

## Quickstart

All artifacts are on Maven Central.

### Spring Boot (zero config)

```xml
<dependency>
  <groupId>io.github.gabrielbbaldez</groupId>
  <artifactId>stacktale-spring-boot-starter</artifactId>
  <version>0.2.0</version>
</dependency>
```

That's it — no logback.xml editing. The starter registers the appender on the root
logger, deduces `← YOUR CODE` packages from your `@SpringBootApplication`, and adds a
servlet filter that opens every story with the HTTP request line (`GET /orders/889/checkout`)
through a stacktale-only logger — **your console never sees those lines**. Tune anything
via `stacktale.*` properties in `application.yml`.

### Plain Logback (any framework, or none)

```xml
<dependency>
  <groupId>io.github.gabrielbbaldez</groupId>
  <artifactId>stacktale</artifactId>
  <version>0.2.0</version>
</dependency>
```

```xml
<appender name="STACKTALE" class="io.github.gabrielbbaldez.stacktale.StacktaleAppender">
  <appPackages>com.your.app</appPackages> <!-- optional but recommended -->
</appender>

<root level="INFO">
  <appender-ref ref="CONSOLE"/>
  <appender-ref ref="STACKTALE"/>
</root>
```

Reports land in `./errors-ai.log`. Point your AI assistant at that file — it announces
itself on startup, and the file header explains the format to any AI that opens it.

### Log4j2

```xml
<dependency>
  <groupId>io.github.gabrielbbaldez</groupId>
  <artifactId>stacktale-log4j2</artifactId>
  <version>0.2.0</version>
</dependency>
```

```xml
<Configuration packages="io.github.gabrielbbaldez.stacktale.log4j2">
  <Appenders>
    <Stacktale name="STACKTALE" appPackages="com.your.app"/>
  </Appenders>
  <Loggers>
    <Root level="info"><AppenderRef ref="STACKTALE"/></Root>
  </Loggers>
</Configuration>
```

Same pipeline, same st/1 format, story correlation via `ThreadContext` — both backends
share `stacktale-core`.

## What gets captured

| Section | What it is |
|---|---|
| headline | The **root cause**, first — wrappers become one `wrapped by:` line each |
| `at` | The culprit frame: first frame of *your* code in the root cause |
| `log` | The message pattern, its args (the values!), and the logger |
| `mdc` | The full MDC at the moment of the error |
| `fields` | **State carried by the exception chain itself** — `orderId`, `statusCode`, `retryable` — read from public getters/fields with hard safety caps |
| `story` | The last events from the same request (MDC `traceId`) or thread — the narrative that led to the error |
| `stack` | Distilled: framework runs collapse into `… 39 collapsed (spring ×24, tomcat ×11)` |
| `env` | App name/version, git sha, Java version, profile, OS — collected once |
| repeats | The same error again doesn't dump again: `━ #c73cf755 repeated 47× ━` |
| restarts | `─── app start … ───` markers separate application runs |

Everything user-controlled is **redacted by default** (JWTs, bearer tokens,
`password=...` pairs, long hex secrets, emails, Luhn-valid card numbers) and flattened to
one line per section. Uncaught exceptions (threads dying without any `log.error`) flow
through the same pipeline.

## Performance (measured, JMH)

| Path | Cost |
|---|---|
| Logback INFO, no appenders (baseline) | 27 ns/op |
| Logback INFO **with stacktale** (story capture) | **137 ns/op** |
| Repeated ERROR, deduplicated (no report written) | 3.9 µs/op |

~110 ns per happy-path event on an ordinary dev machine (JDK 21, Windows, single JMH
fork — reproduce with [`AppendBenchmark`](stacktale/src/test/java/io/github/gabrielbbaldez/stacktale/AppendBenchmark.java)).
Writing a full report costs milliseconds — errors are rare, that's the deal.

## Does it actually help? (blind A/B)

We ran a blind test: [`BlindTestScenario`](stacktale/src/test/java/io/github/gabrielbbaldez/stacktale/BlindTestScenario.java)
simulates a checkout that dies on a total-limit sanity check while 6 other request
threads produce realistic traffic. The true root cause (a stale-price fallback mixing
USD prices into a BRL order) never appears in the stack trace — only in the events
before the error. The SAME run wrote both a classic interleaved log (95 lines) and a
stacktale report (27 lines). Two fresh AI agents, identical prompts, no source access,
each got one artifact.

Honest results: **both found the root cause** (95 lines still fit a strong model's
attention) — but the report needed **~4× less input**, zero effort separating 7 threads
of noise, and its reader inferred blast radius from the format itself (no `repeated N×`
lines → single occurrence). The structural argument stands: classic logs grow with
traffic; a stacktale report stays ~27 lines per error, story attached.

## Configuration

Everything is optional — as appender properties in `logback.xml`, or `stacktale.*` in
`application.yml` with the starter:

| Property | Default | What it does |
|---|---|---|
| `file` | `errors-ai.log` | Where reports go |
| `appPackages` | *(heuristic / auto in Spring)* | Comma-separated roots marked `← YOUR CODE` |
| `storySize` | `15` | Events kept per context for the story |
| `storyWindowSeconds` | `60` | Max age of story events |
| `dedupWindowSeconds` | `300` | One full report per error per window |
| `maxFileSizeMb` | `5` | Size-based rotation threshold |
| `maxBackups` | `1` | Rotated backups kept (0 = start fresh) |
| `truncateOnStart` | `false` | Drop the previous session's reports on startup |
| `installUncaughtHandler` | `true` | Report uncaught exceptions too |
| `reportErrorsWithoutThrowable` | `true` | `log.error(...)` without exception still reports |
| `captureExceptionFields` | `true` | Read exception getters into `fields:` |
| `redactionEnabled` | `true` | Mask secrets/PII in report content |
| `redactPattern` (repeatable) | — | Extra redaction regexes |
| `correlationMdcKeys` | `traceId,correlationId,requestId` | MDC keys that group the story |
| `zone` | system | Timezone for report timestamps |
| `requestLogging` *(starter)* | `true` | HTTP request lines into the story |

Async work: wrap hops with [`StacktaleExecutors`](stacktale/src/main/java/io/github/gabrielbbaldez/stacktale/StacktaleExecutors.java)
(`wrap(executor)` / `wrap(runnable)`) so the MDC — and with it the story — survives
`CompletableFuture`, pools and virtual threads. Apps already propagating context
(Micrometer, Reactor) need nothing.

## Guarantees

- **Never breaks your app.** Any internal failure degrades stacktale to a no-op —
  including invalid configuration at startup (a broken `<file>` can't fail your boot).
- **Cheap happy path.** ~110 ns per non-error event, measured. No I/O off the error path.
- **The format is a public API.** `st/1` is pinned by golden-file tests and the file
  header teaches it to any AI. Format changes are deliberate and versioned.
- **Nothing leaves the machine.** A local file, same trust boundary as your logs. No
  network, no phone-home. Redaction on by default anyway.

## Limitations (honest ones)

- The story follows MDC correlation keys, falling back to same-thread. Fully async apps
  **without MDC propagation** get a fragmented story — use `StacktaleExecutors` or any
  context-propagation library.
- stacktale organizes what your app already logs. If the app logs nothing before the
  error, there is no story to tell.
- Redaction is regex-level hygiene, not semantic PII detection.
- `StacktaleExecutors` propagates the SLF4J MDC; in Log4j2-native apps (no SLF4J
  binding), propagate `ThreadContext` across hops yourself.

## Roadmap

Maven Central publication ([#2](https://github.com/GabrielBBaldez/stacktale/issues/2))
and real-world format validation ([#4](https://github.com/GabrielBBaldez/stacktale/issues/4))
— full list in [issues](https://github.com/GabrielBBaldez/stacktale/issues).
Contributions welcome: see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache-2.0](LICENSE)
