---
status: in progress
priority: P2
complexity: 3
readiness: 100%
agent: Claude Code / claude-fable-5-2
---

# T9. Strip dead leftovers of the Rust port

The Kotlin port still carries code, comments and config entries that came over from the Rust project
and that nothing uses any more: functions without callers, duplicate helpers, stale "lands later"
comments, and build config that names modules which no longer exist. The creator asked to remove them.

Scope, set by the creator: only what is dead. Format compatibility with the Rust binary (CLI, config,
on-disk files) stays, and so do `PORTING.md` and `port-plan.md`. Done means every removal is backed by
a reference search, the suite is green, and `done.md` lists what was removed, what was kept and why,
and what needs the creator's decision.

## Execution plan

1. Verify each candidate from the T7 dead-code scan by reference search (`git grep` over main, test
   and build files); a candidate with a live reader stays.
2. Remove, one concern per commit: unused functions and fields; duplicate helpers (keep one copy and
   point the callers at it); `TaskAdapter.maxId` and its implementations, which only tests call;
   Windows-only branches whose callers always pass `false`; stale comments and unused imports; build
   and script entries for modules that do not exist.
3. Leave test seams, the public SDK API and unwired features in place and list them in `done.md`.
4. Run the suite and the size check, regenerate a detekt baseline only where an id went stale.

Stacked on T7 (`t7-review-fixes`).

Check: `./gradlew macosArm64Test cliTest e2eTest` passes and `mise run size-check` stays under the
budget on the PR head.
