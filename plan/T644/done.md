The tests leak their temp dirs into `$TMPDIR`: on the creator's Mac about 24,000 `rtok-mem-*` (`Runtime::in_memory` in `src/plugin.rs` creates one per call and never removes it), 6,300 `rtok-tui-*`, 2,000 `rtok-guard-*`, 1,300 `rtok-usage-*` and more. A directory that size makes `realpath` in it slow under load (T643). Each test helper that makes a temp dir removes it (a guard or `tempfile::TempDir`), `Runtime::in_memory` first.

Plan: 329 `temp_dir()` calls in 158 files are far past one task's 500 lines, so two layers instead of a rewrite of every helper:
1. `Runtime::in_memory` (`src/plugin.rs`), the 24,000 of them: the runtime owns its dir and removes it when dropped. A private guard, not `tempfile`: that would be a new runtime dependency of the shipped binary for a test helper.
2. Everything else: the nextest setup script in `.config/nextest.toml` points `TMPDIR` (`TMP`/`TEMP` on Windows) at a per-checkout dir it empties at the start of each run, the way it already treats `target/test-home`. On Unix that dir is `/tmp/rtok-test-<cksum of the checkout path>`, not under `target/`: a home under a deep worktree path would push `hook.sock` past the 100-byte `sun_path` limit `rtok_hook::endpoint` enforces. Windows uses `target\test-home\tmp`.
3. Tests: the drop test in `src/plugin.rs`, and a `testutil` test that under nextest `temp_dir()` is that dir. Then a full `just test` with a count of `rtok-*` in the real `$TMPDIR` before and after.

Result: `Runtime` holds a `ScratchDir` that removes the dir `in_memory` made; nextest runs every test with `TMPDIR` at `/tmp/rtok-test-<cksum>` (Windows: `target\test-home\tmp`), emptied by the setup script. Helpers outside nextest (a plain `cargo test`) still write to the real temp dir.

Check: `a_dropped_in_memory_runtime_leaves_no_directory` and `nextest_runs_under_the_test_tmp` failed before and pass after. A full `just test` (3736 passed): `rtok-*` in the real `$TMPDIR` 125,701 before and after, 0 entries newer than the run's start; the run's own leftovers, 850 entries, stay in its `/tmp/rtok-test-*` until the next run empties it.

Status: done 2026-10-13
Model: Claude Code / claude-opus-5-5
