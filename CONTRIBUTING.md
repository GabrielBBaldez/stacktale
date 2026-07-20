# Contributing to stacktale

Thanks for stopping by the campfire. 🔥

By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

New here? The [`good first issue`](https://github.com/stacktale/stacktale/labels/good%20first%20issue)
label marks issues written to be picked up cold — each one names the files to touch and how
to verify. Comment on the issue to claim it.

## Build & test

```bash
mvn verify        # JDK 17+ (CI runs 17 and 21)
```

The full suite must be green before a PR. Coverage report lands in
`target/site/jacoco/index.html`.

## The one rule that matters: the report format is a public API

The `st/1` report format is specified in [docs/FORMAT.md](docs/FORMAT.md) and pinned by
golden-file tests (`stacktale-core/src/test/resources/golden/`). If your change makes a
golden test fail, that is not a test to "fix" — it is a **format change**, and format
changes need to be deliberate: called out in the PR description, reflected in FORMAT.md,
and (post-1.0) a format version bump. AI tools and the MCP server parse these files; we
don't move their cheese silently.

## Working style

- **TDD**: new behavior arrives with the test that demanded it. Bug fixes start with a
  red test reproducing the bug.
- **The never-throw guarantee is sacred**: nothing may propagate out of the appender
  path into the host app. If you touch `StacktaleAppender`, `ReportWriter` or any
  capture code, think about the hostile case (poisonous `toString()`, broken config,
  full disk) and test it.
- **Cheap happy path**: non-error events must stay allocation-light. No I/O, no
  formatting beyond what the story needs.
- Commits: conventional prefixes (`feat:`, `fix:`, `docs:`, `chore:`, `test:`),
  imperative mood, reference issues (`Closes #N`).

## Pull requests

- One logical change per PR.
- Explain the *why*, link the issue.
- New config properties need: a setter (Joran naming), a default that keeps current
  behavior, coverage in `LogbackXmlConfigTest`, and a row in the README config table.

## Reporting bugs

Please include the `errors-ai.log` block (redacted as needed — stacktale itself redacts
common secrets, but double-check), your `logback.xml` appender config, and Java/Logback
versions. The issue template asks for exactly these.
