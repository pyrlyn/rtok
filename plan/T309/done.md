`tests/latency.rs` `hook_returns_despite_exclusive_lock` flaked on unrelated PRs on 2026-09-27: `ci / check (macos-latest)` for pyrlyn/rtok#461 ("hook waited 102.74ms", 100 ms bound, job 108701481370) and `ci / windows (2/2)` for pyrlyn/rtok#466 ("313.66ms", 250 ms bound, job 108706221829); both green on rerun. The `calls` assert (T237) is the proof that the hook gave up instead of waiting; the wall bound is a fixed guess at runner speed and shares the CPUs with the whole suite. Done means the test keeps catching a hook that waits on the lock, without depending on how loaded the runner is.

Plan: (1) run the test alone under nextest (`threads-required`, `.config/nextest.toml`), as T237 did for the skill digest test; (2) derive the wall bound from the holder instead of the platform: the hook must return within half the hold (`HOLD / 2`, 250 ms everywhere), so it cannot have waited for the release; (3) move the few-ms precision to where it is exact: a compile-time assert in `src/hooks/mod.rs` that each `LOCK_WAIT` wait stays within half the 10 ms hook budget (D1).

Check: `mise exec -- cargo nextest run -p rtok --test latency`, repeated; mutations on `LOCK_WAIT.busy`.

Result: the test runs alone and asserts `took < HOLD / 2` (250 ms) on every platform next to the unchanged `calls` check; `src/hooks/mod.rs` has `const _: () = assert!(...)` holding `LOCK_WAIT.busy` and `.migrate` at ≤ 5 ms. Six local runs green (hook test 0.55–0.65 s including the 500 ms hold). Mutations: `busy` 200 ms fails to compile; with the assert commented out, `busy` 100 ms fails the wall bound ("hook waited 258 ms" — the per-statement waits add up) and 300 ms fails the `calls` assert ("outwaited … 532 ms"). The hot path and `LOCK_WAIT` values are unchanged.

Status: done 2026-09-28
Model: Claude Code / claude-opus-5-5
