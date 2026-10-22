From run 36046793837 (PR #335, 2026-09-24); the test passed in PR #336's `windows` job, which dropped its filter (T83.9), so it is intermittent. The `Stop` hook's flush child is still running when the `SessionEnd` hook fires; on Windows the child likely holds the store file, the `SessionEnd` write fails open, and `ended_at` stays empty. Find which write loses (store busy timeout, file lock), make `SessionEnd` survive a concurrent flush child, and drop the test from the `cfg(windows)` filter.

Do (Claude Code / claude-opus-5-5, 2026-09-24): not Windows-only — PR #357's `ubuntu-latest` job failed the same way. The hook waits 5 ms on another writer (T178 `LOCK_WAIT`) at store open, `record_call` and `end_session`; the flush child `Stop` spawned is still writing its watermarks, so `SessionEnd` was skipped, and nothing ever repeats it — `ended_at` stayed empty and the session's OTel root span never shipped. Fix in `src/hooks/mod.rs`: a `SessionEnd` that meets a lock at any of those three writes hands its (Claude-shaped) stdin to a detached `rtok hook SessionEnd` with `RTOK_HOOK_DEFERRED=1`, which waits `LockWait::STEADY` and never defers again — the same hand-off `Stop` uses for the flush, so the hook still returns within budget. New test `hook_fail_open::a_locked_session_end_is_deferred_not_lost` holds the writer lock, checks the hook returns in under 500 ms and that `ended_at` lands after release; `otel::stop_hook_spawns_the_flush_and_stays_under_10ms` polls for `ended_at` instead of reading it once; the test left the `cfg(windows)` filter in `.config/nextest.toml`.

Check: the test passes in the `windows` CI job; `just check` stays green.

Check result: the new test fails against origin/main's `src/hooks/mod.rs` and passes with the fix; `just check` green on macOS (1771 passed); PR #364 merged only with green `check` and `windows` jobs.

Status: done 2026-09-25
Model: Claude Code / claude-opus-5-5
