# Changelog

All notable changes to stacktale are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[SemVer](https://semver.org/). The report format (`st/1`) is versioned independently
and pinned by golden-file tests.

## [0.4.0] — 2026-07-10

Production hardening and the agentic loop.

- **MCP push notifications**: the server exposes the report file as an MCP resource with
  subscribe support — your AI assistant is notified the moment a new error lands (file
  watcher → `notifications/resources/updated`) instead of polling. `STACKTALE_FILE` env
  var as an alternative to `--file`; full per-client setup in `docs/mcp-setup.md`.
- **Error-storm control**: `maxReportsPerMinute` (0 = off) caps full reports; a cascade
  of *distinct* errors becomes a throttled `storm: N suppressed` line instead of flooding
  the file and rotating history away when you need it most.
- **Agent filters**: `-javaagent` args gained `excludes=`, `maxFrames=`,
  `maxValueLength=`, and `renderToString=false` — a privacy mode that records an object's
  type and nullness but never its `toString()`.
- **Reactive (WebFlux)**: a reactive filter opens the story with the request line and
  propagates the trace id across scheduler hops via Reactor context propagation.
- **Formal `st/1` specification** (`docs/FORMAT.md`) — the normative format spec, now that
  external contributors and the MCP server parse it. The golden files are its conformance
  suite.
- **Compatibility matrix** in CI (weekly + on POM changes): Logback 1.4/1.5, Log4j2 2.20,
  Spring Boot 3.2/3.3/3.5 — supported ranges documented and each backed by a passing build.
- **One-click release** workflow (`workflow_dispatch`).
- Container-echo suppression and burst-counter flush (from real-world dogfooding); Log4j2
  non-parameterized message types; non-English redaction keywords.

[0.4.0]: https://github.com/stacktale/stacktale/releases/tag/v0.4.0

## [0.3.1] — 2026-07-10

Patch release — fixes a critical agent startup bug in 0.3.0. **If you use
`stacktale-agent`, upgrade.** (The core, logback, log4j2, starter and mcp artifacts are
functionally unchanged from 0.3.0; only bug fixes.)

- **CRITICAL (agent)**: `stacktale-agent-0.3.0` aborts the JVM at startup when attached as
  documented — its manifest declared `Can-Retransform-Classes=false` while the code
  requested retransformation, and `premain` didn't guard against it. Fixed: the manifest
  allows retransform, installation degrades gracefully when the runtime doesn't support
  it, and `premain` disables the agent instead of propagating. A packaging integration
  test now guards the manifest contract.
- **Agent**: package matching now respects package boundaries (`packages=com.a.orders`
  no longer instruments `com.a.ordersprocessing`).
- **Pipeline**: a failing report shipper (`emitReportsToLogger`) no longer rolls back
  dedup state after the report was already written (which duplicated the next report).
- **WebFlux filter**: guarded against `MDC.put` throwing on a partial SLF4J binding — it
  can no longer fail the HTTP request.
- **MCP**: scans all contiguous rotated backups (not just `.1`–`.9`); unknown methods
  return JSON-RPC `-32601`.
- **Dedup**: repeat counts are marked written only after a successful append.

[0.3.1]: https://github.com/stacktale/stacktale/releases/tag/v0.3.1

## [0.3.0] — 2026-07-10

The "capture everything, everywhere" release — closes the entire original backlog.

- **`stacktale-agent`** (new module): optional `-javaagent` that records **method
  arguments at the throw site** into a `captured:` report section —
  `confirmOrder(orderId=889, customer=null)` appears even when the code logged nothing.
  Zero happy-path overhead (advice runs only on exceptional exit), bounded, redacted,
  real parameter names with `-parameters`.
- **`stacktale-mcp`** (new module): read-only MCP server — AI assistants query reports
  as tools (`list_errors`, `get_report`, `errors_since`) instead of reading files.
- **Reactive story (WebFlux)**: a reactive filter opens the story with the request line
  and plants the traceId in the Reactor Context; automatic context propagation keeps the
  story whole across `flatMap`s and scheduler hops.
- **Container-echo suppression**: Tomcat/Spring re-logs of a failure the same thread just
  reported are skipped (configurable window; apps that don't log first keep their
  container report). Found by real-world dogfooding.
- **Burst counter flush**: repeat counts silenced by the summary throttle are written on
  shutdown — the file never understates a burst.
- **Report shipping**: `emitReportsToLogger=true` re-emits each block as ONE event via
  logger `stacktale.reports` for existing log shippers.
- **Redaction**: non-English secret keywords (senha, contraseña, passwort, chave…).
- **Log4j2**: non-parameterized Message types (MapMessage & co.) render readable `log:` lines.
- README: measured token economics — 98.3% session savings (60×), 80.6% per error.

[0.3.0]: https://github.com/stacktale/stacktale/releases/tag/v0.3.0

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

[0.2.0]: https://github.com/stacktale/stacktale/releases/tag/v0.2.0

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

[0.1.0]: https://github.com/stacktale/stacktale/releases/tag/v0.1.0
