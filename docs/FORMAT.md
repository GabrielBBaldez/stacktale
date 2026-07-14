# The `st/1` report format

This is the normative specification of the format stacktale writes to `errors-ai.log`.
It is a **public API**: the golden-file tests in `stacktale-core/src/test/resources/golden/`
are its executable examples, and any change here is a deliberate, versioned event.

The format is line-oriented UTF-8 text, designed to be equally readable by a human, an AI
assistant, and a simple parser. Parsers **must** tolerate unknown lines and sections
(forward compatibility): a future `st/1.x` may add sections, and a reader that skips what
it doesn't recognize keeps working.

## 1. File structure

A report file is a **header** followed by zero or more **entries**, in append order:

```
<file header>
<entry>
<entry>
...
```

An entry is one of:

- a **report block** (a full error report),
- a **repeat line** (`━ #<id> repeated N× ━`),
- a **session marker** (`─── app start … ───`),
- a **storm line** (`━ storm: N report(s) suppressed ━`).

Rotated backups (`errors-ai.log.1`, `.2`, …) each carry their own file header and follow
the same grammar. Chronological reconstruction across backups uses entry timestamps.

## 2. File header

Every file (including each rotated backup) begins with a block of `#`-prefixed comment
lines describing the format. A parser **may** skip all leading `#` lines. The header is
self-describing by design — an AI that opens the file with no prior knowledge learns the
format from it.

```
# AI-oriented error reports (format st/1, https://github.com/stacktale/stacktale)
# Each report is delimited by "━━━ ERROR #<id> ━━━" ... "━━━ END #<id> ━━━".
# ...
```

The exact header text is not part of the contract; the `format st/1` token on the first
line is the format-version signal.

## 3. Report block

```
━━━ ERROR #<id> ━━━ <timestamp> thread=<thread> ━━━
<headline>
[at <culprit-frame> ← YOUR CODE]
[wrapped by: <Type>("<message>") at <frame>]        (zero or more, root-outward)
log: "<message-pattern>" [args=[<v>, …]] logger=<abbreviated-logger>
[mdc: <k>=<v> …]
[fields: <k>=<v> …]
[captured (method args at throw site, via stacktale-agent):
  <Type.method(arg=value, …)>                        (one or more)]
<blank line>
[story (<label>, last N events, <span>ms):
  <HH:mm:ss.SSS> <LEVEL> <logger>  <message>[   ← this error]]
<blank line>
[stack (distilled, X of Y frames):
  <frame>[ ← culprit]
  … N collapsed (<group> ×n, …)
  [suppressed: …]]
<blank line>
env: <environment line>
━━━ END #<id> ━━━
```

Delimiters:

- The opening line **starts** with `━━━ ERROR #` at column 0. (The file header only
  quotes this token mid-line, so a parser keying on `^━━━ ERROR #` never mistakes the
  header for a report.)
- The closing line is `━━━ END #<id> ━━━` with the same `<id>`.
- A block whose closing line is absent (a file truncated mid-write, e.g. a `kill -9`) is
  incomplete; parsers **must** discard it rather than emit a partial entry.

Fields:

| Token | Meaning |
|---|---|
| `<id>` | 8 lowercase hex chars — a stable fingerprint of (root type + culprit frame + digit-normalized message). Same error ⇒ same id across occurrences and restarts. |
| `<timestamp>` | `yyyy-MM-dd HH:mm:ss.SSS` in the configured zone |
| `<thread>` | the thread that logged the error |
| `<headline>` | the **root cause**: `<SimpleType>[: <message>]`. For a report with no throwable: `ERROR (no exception): <formatted message>`. |
| `at …` | the culprit frame; `← YOUR CODE` is appended **only** when it is genuinely app code (an app-package frame). Absent when the stack is empty. |
| `wrapped by:` | one line per wrapper exception, innermost-wrapper-first (root cause is the headline, wrappers climb outward). |
| `log:` | the original SLF4J/Log4j2 message **pattern** (not the interpolated text), its `args`, and the logger name abbreviated `c.a.s.OrderService`. |
| `mdc:` / `fields:` | space-separated `key=value` pairs, keys sorted. Omitted when empty. |
| `seen:` | recurrence — `N× this session, first at <HH:mm:ss.SSS>`. Present **only** when the error has occurred before this session (its absence means the error is new). Session-scoped: resets on restart. |
| `captured:` | present only when the agent is attached; method frames with argument values. |
| `story` | events leading up to and including the error, oldest first. `<label>` is `traceId=…` (correlated) or `thread <name>` (fallback). The error's own line ends with `   ← this error`. A `… N earlier event(s) older than the story window omitted` line appears when events were dropped by age (vs never logged). Omitted when empty. |
| `stack` | the distilled stack: shown frames plus `… N collapsed (<framework groups>)` markers; the culprit frame ends with `← culprit`. Omitted for reports with no throwable. |
| `env:` | `app=<name> <version> (git <sha>) \| java <ver> \| profile=<p> \| <os>`; unknown parts degrade (`app=?`, no `(git …)`, no `profile=`). |

## 4. Escaping

Every value that originates from user data (messages, args, MDC values, exception
messages, story messages, captured values) is **flattened to a single line**: embedded
`\r`, `\n`, and `\r\n` are replaced with the literal two characters `\n`. This preserves
the one-line-per-field invariant that makes the format parseable. No other escaping is
applied; the delimiter strings use the `━` (U+2501) box-drawing character, which does not
occur in normal log text.

Redaction (on by default) replaces matched secrets with `███` **before** the value
reaches the file — a parser sees the masked form.

With **correlation** enabled (opt-in, off by default) a masked value carries a stable
suffix: `███(a1b2)`, where `a1b2` is four lowercase-hex characters of a keyed hash of the
raw value. The same value yields the same suffix within one process run, so a reader can
tell whether the *same* secret keeps recurring without the value ever being exposed; the
suffix is one-way (a per-process random key) and is applied only to values long enough
that it cannot be brute-forced from a small domain. A parser that doesn't care may treat
`███(…)` exactly like `███`.

## 5. Non-report entries

```
━ #<id> repeated N× (last <HH:mm:ss.SSS>) ━
```
The same error occurred again; `N` is the cumulative count. Emitted instead of a new
block for repeats within the dedup window, throttled.

```
─── app start <timestamp> (pid <pid>) ───
```
A new application run began appending to an existing file. Separates sessions.

```
━ storm: N report(s) suppressed (rate limit M/min) ━
```
A flood of **distinct** errors exceeded the report rate limit; `N` full reports were
suppressed to protect the file. The errors still happened — this line is the acknowledgement.

## 6. Versioning & compatibility

- The format version is `st/1`, declared in the file header.
- **Additive** changes (new optional sections, new non-report lines) do **not** bump the
  major and **must** be skippable by conforming parsers.
- A **breaking** change (altered delimiters, removed/renamed sections, changed field
  semantics) bumps to `st/2` and is called out in the changelog and design doc.
- The golden files are the conformance suite: a diff there is either a bug or a
  deliberate, documented format change.

## 7. JSON output (`st-json/1`)

Set `format=json` (an appender attribute, or `stacktale.format` with the Spring starter,
or the `…StacktaleJulHandler.format` property) to write **NDJSON** instead of the text
blocks: one compact JSON object per line. It carries the same information, but every
section is an addressable field — for parsers, pipelines and dashboards rather than an LLM
reading raw. (The text format is denser per token, so it stays the default.) The bundled
`stacktale-mcp` server reads the **text** format; with `format=json` you consume
`errors-ai.log` with your own JSON tooling.

Each line is one entry, discriminated by `type`:

- `header` — `{"type":"header","format":"st-json/1","docs":"…"}` (once per file)
- `report` — a full error report (below)
- `repeat` — `{"type":"repeat","id":"…","count":N,"last":"<ISO-8601>"}`
- `session` — `{"type":"session","ts":"<ISO-8601>","pid":N}`
- `storm` — `{"type":"storm","suppressed":N,"limit":M}`

A `report` object (pretty-printed here; on disk it is one line):

```json
{
  "type": "report",
  "id": "a1b2c3d4",
  "ts": "2026-07-10T20:16:40.412Z",
  "thread": "http-nio-8080-exec-2",
  "error": {
    "type": "IllegalStateException",
    "message": "payment gateway refused",
    "culprit": { "frame": "PaymentService.charge(PaymentService.java:44)", "appCode": true },
    "wrappedBy": ["CheckoutException(\"checkout failed\") at CheckoutService.confirm(…)"]
  },
  "log": { "pattern": "charge failed for order {}", "args": ["889"], "logger": "com.acme.shop.PaymentService" },
  "mdc": { "traceId": "7c2e" },
  "fields": { "orderId": "889", "retryable": "false" },
  "captured": ["PaymentService.charge(orderId=889, amount=149.90)"],
  "recurrence": { "count": 3, "firstSeen": "2026-07-10T20:16:40.000Z" },
  "story": {
    "label": "traceId=7c2e",
    "omittedByAge": 2,
    "events": [
      { "ts": "…", "level": "INFO", "logger": "CheckoutService", "message": "confirming order 889" },
      { "ts": "…", "level": "ERROR", "logger": "PaymentService", "message": "charge failed for order 889", "thisError": true }
    ]
  },
  "stack": { "shown": 1, "total": 32, "frames": ["…"], "suppressed": [] },
  "env": "app=shop-api 1.4.2 (git 7e3c1f) | java 21 | profile=prod | linux"
}
```

Rules:

- Timestamps are ISO-8601 with fixed millisecond precision (`.SSS`) and an offset (`Z` for UTC).
- Optional members (`mdc`, `fields`, `captured`, `recurrence`, `error.wrappedBy`,
  `error.culprit`, `stack`, `env`) are **omitted** when empty — absence is the signal.
- A no-throwable report has `"error": { "noException": true, "message": "…" }` and no `stack`.
- Redaction is identical to the text format: a secret-named key masks its value, a
  secret-position arg becomes `███`, secret-shaped values are redacted. Multi-line values
  keep their newlines (JSON-escaped) instead of being flattened.
- The `logger` is the full name (the text format abbreviates it for display; JSON does not).
- Same version discipline as §6: additive members don't bump the major; a breaking change
  bumps to `st-json/2`.
