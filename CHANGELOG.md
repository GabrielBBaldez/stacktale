# Changelog

All notable changes to stacktale are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[SemVer](https://semver.org/). The report format (`st/1`) is versioned independently
and pinned by golden-file tests.

## [0.2.0] — 2026-07-10

First release on **Maven Central**.

- **Log4j2 support**: new `stacktale-log4j2` module — same pipeline, same st/1 format,
  story correlation via `ThreadContext`, XML plugin appender (`<Stacktale …/>`).
- **`stacktale-core`**: the report pipeline is now framework-agnostic; the Logback
  artifact keeps its coordinates and behavior as a thin adapter.
- **Redaction hardening** (cross-audit findings): name-based redaction now reaches
  `fields:`, `mdc:` and `args=` (secret arg positions derived from the message pattern);
  short Basic credentials and JSON-quoted keys (`"password":"…"`) are masked; secret
  keywords now include non-English names (senha, contraseña, passwort, chave…).
- Spring starter: the auto-configured appender is replaced (never reused stale) across
  application contexts in the same JVM, and detaches on context close.
- `stacktale active` is no longer announced when the pipeline degraded to no-op.
- FieldExtractor reads public getters on package-private exception classes
  (`trySetAccessible`, degrading quietly under closed JPMS modules).
- Log4j2 adapter drops the trailing throwable from `args=` (Log4j2 keeps it inside
  `Message.getParameters()` after extraction).
- Dependency refresh via Dependabot (Logback 1.5.38, AssertJ 3.27.7, Surefire 3.5.6,
  JaCoCo 0.8.15, actions/checkout v7, setup-java v5).

[0.2.0]: https://github.com/GabrielBBaldez/stacktale/releases/tag/v0.2.0

## [0.1.0] — 2026-07-09

First release. Everything below is new.

### The library (`stacktale`)

- **`StacktaleAppender`** for Logback: intercepts `ERROR` events and writes complete,
  AI-oriented reports to a separate `errors-ai.log` — the human log stays untouched
  (one pointer line links the two).
- **`st/1` report format**, self-describing (the file header teaches it to any reader)
  and pinned by golden-file tests: root-cause-first headline, `← YOUR CODE` culprit
  frame, log args, MDC, exception `fields:`, the story, a distilled stack, environment.
- **Story**: ring buffers of recent events per MDC correlation key (`traceId`, …) with
  per-thread fallback — the narrative that led to the error, attached to the report.
- **Exception fields**: public getters/fields across the whole cause chain read into
  `fields:` (`orderId=123 retryable=false`) with hard safety caps; the state classic
  formats throw away.
- **Stack distilling**: framework frame runs collapse with counts
  (`… 39 collapsed (spring ×24, tomcat ×11)`); wrappers shrink to one line each.
- **Dedup**: one full report per error fingerprint per window; repeats become throttled
  `repeated N×` lines. Fingerprints are 32-bit (birthday-safe for the dedup map size).
- **Redaction on by default**: JWTs, bearer/basic tokens, secret key=value pairs, long
  hex, emails, Luhn-valid card numbers; extra patterns configurable.
- **Session markers** (`─── app start … ───`) separate application runs;
  `truncateOnStart` for dev loops; rotation with configurable backup depth.
- **Uncaught exception handler** (optional, on by default) funnels thread deaths through
  the same pipeline.
- **`StacktaleExecutors`**: MDC-propagating wrappers so the story survives async hops
  and virtual threads.
- **Never-throw guarantee**: hostile input (poisonous `toString()`, malformed metadata
  files, invalid config, full disk) degrades stacktale, never the host app.
- Performance (JMH): ~110 ns per happy-path event over the Logback baseline; 3.9 µs per
  deduplicated repeat error.

### Spring Boot starter (`stacktale-spring-boot-starter`)

- Zero-config auto-registration on Logback's root logger; `stacktale.*` properties.
- `appPackages` deduced from the `@SpringBootApplication` package.
- Servlet filter opens every story with the HTTP request line through a stacktale-only
  logger (additivity off — the console never sees it); 5xx responses close the story
  with status and duration.

### Validation

- 72 tests (unit, golden-file, integration, concurrency, hostile-input, virtual
  threads, real embedded-Tomcat starter test), line coverage 93%+.
- Blind A/B on AI agents documented in the README.

[0.1.0]: https://github.com/GabrielBBaldez/stacktale/releases/tag/v0.1.0
