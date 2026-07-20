<!-- Thanks for contributing. Keep this short — a sentence per box is plenty. -->

## What & why

<!-- What changes, and what problem it solves. The *why* is the part reviewers can't infer. -->

Closes #

## How it was verified

<!-- Pick one and delete the rest. -->

- [ ] `mvn verify` is green (JDK 17+)
- [ ] Documentation-only — nothing to build

## Checklist

- [ ] One logical change (unrelated fixes belong in their own PR)
- [ ] Behavior changes arrive with the test that demanded them; bug fixes start from a
      failing test
- [ ] **No golden-file test changed** — `st/1` is a public API. If a golden test did
      change, that is a deliberate format change: say so below, and update
      [docs/FORMAT.md](https://github.com/stacktale/stacktale/blob/main/docs/FORMAT.md)
- [ ] New config property? It has a setter (Joran naming), a default that preserves
      current behavior, coverage in `LogbackXmlConfigTest`, and a row in the README
      config table

<!--
First time here? Everything above is explained in CONTRIBUTING.md:
https://github.com/stacktale/stacktale/blob/main/CONTRIBUTING.md

Don't worry about getting every box right — open the PR and we'll work through it.
-->
