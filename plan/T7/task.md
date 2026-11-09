---
status: in progress
priority: P1
complexity: 5
readiness: 10%
agent: Claude Code / claude-opus-5-5
---

# T7. Whole-repo code review: bug fixes, K1–K5 and optimizations

The creator asked for a code review of the whole Kotlin port on `origin/main`: find and fix bugs, and
optimize the code for speed and memory, simplicity and binary size. The audit findings K1–K5 in
[TODO.md](../../TODO.md) are in scope; K6 (a `FileSystem` interface) is not.

Done means:

- Every non-test source file (about 100k lines in 30 review units) and the binary-size setup were
  reviewed for bugs and optimizations. Every finding was checked by independent verifiers; only
  confirmed findings are acted on.
- Confirmed bugs are fixed, each with a regression test where the code can be tested without a real
  host.
- K1–K5 are fixed with the tests TODO.md names, and their entries leave TODO.md (K6 stays).
- Confirmed optimizations that keep behavior identical are applied. Anything that would change
  behavior or needs a product decision goes to `done.md` for the creator instead.
- Findings that were not fixed (refuted, out of scope, waiting for a decision) are summarized in
  `done.md`.

## Execution plan

1. Split the non-test sources into review units and run a review workflow: per unit a bug reviewer,
   an optimization reviewer and a completeness pass; each bug goes to one or two skeptical verifiers
   (and a judge when they disagree), optimizations to batched skeptics.
2. Triage the confirmed list. Fix per unit in parallel (edits only, disjoint files), then K1–K5.
3. Build and run the full suite, fix the fallout, then review the whole diff adversarially for
   regressions.
4. Commit in small commits, one concern each (at most 40 files), open the PR, write `done.md`.

Check: `./gradlew macosArm64Test cliTest e2eTest` passes and `mise run size-check` stays under the
budget on the PR head.
