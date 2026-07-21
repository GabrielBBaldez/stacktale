# Contributing to stacktale

Thanks for stopping by the campfire. 🔥

By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

New here? The [`good first issue`](https://github.com/stacktale/stacktale/labels/good%20first%20issue)
label marks issues written to be picked up cold — each one names the files to touch and how
to verify.

## Claim the issue before you start

**Comment on the issue saying you'd like to take it, and wait for a reply before writing
code.** A short "I'd like to work on this" is enough — no need to restate the issue or
explain your plan.

This is not bureaucracy: it is the only thing standing between you and someone else
spending an evening on the same fix. It also lets us tell you upfront if an issue is
already half-done, blocked on a decision, or narrower than it looks — cheaper to hear in a
comment than in a review.

You'll normally get an answer within a day or two. If nobody replies after that, go ahead
and open the PR anyway; a stale issue is our problem, not yours.

Two exceptions, where you should just open the PR: an obvious typo or broken link, and
anything with a `good first issue` label that already has your name on it from a previous
conversation.

Unclaimed PRs still get reviewed — we won't close your work over process. But if two
arrive for the same issue, the one that claimed it first gets the review.

## Build & test

```bash
mvn verify        # JDK 17+ (CI runs 17 and 21)
```

The full suite must be green before a PR. Coverage report lands in
`target/site/jacoco/index.html`.

CI also runs `scripts/check-readme-compat.sh`, which fails when the README's
**Compatibility** table disagrees (at major.minor precision) with the versions
the build actually tests — the pom properties and the `compat.yml` matrix. If
that check fails after a dependency bump, update the table in `README.md`.

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
