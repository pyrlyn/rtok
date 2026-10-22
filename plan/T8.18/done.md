**T8.18 the watcher tests stop racing the filesystem** · T8.16 · `src/plugins/graph/watch.rs`
Do: `watcher_reindexes_new_file_while_calls_read_nothing` and
`watchman_without_socket_falls_back_to_notify` give the watcher one second to observe a write and
re-index. On a loaded machine FSEvents does not deliver in that window, and both tests have failed
and then passed on a re-run three times on 2026-09-09 alone. A flaky gate is worse than a slow one:
it trains everyone to re-run instead of to read. Replace the fixed deadline with a poll until a
generous cap (the deadline is then only reached when the watcher is genuinely broken), and assert on
the store's state rather than on timing.
Check: both tests pass 20 consecutive runs (`cargo test --lib plugins::graph::watch` in a loop) and
still fail within the cap when the watcher is disabled.
Status: done 2026-09-09 · Model: Opus 5 (subagent)
Check result: green. `wait_contains` now polls the store until a 10 s `REINDEX_CAP` instead of
looping a fixed 25 × 50 ms, and the separate `t0.elapsed() <= 1 s` assertion — the actual flake, since
FSEvents sometimes delivered between 1 s and the old 1.25 s cap — is gone from both tests. 20
consecutive `cargo test --lib plugins::graph::watch` runs: 5 passed, 0 failed, every time. The
negative case was proved rather than assumed: a scratch test that seeds the index and never spawns
the watcher fails at the cap with `watcher did not re-index within 10s: expected watched to contain
"watched.rs:1", store holds "no definition of watched"`, then was reverted. `assert_eq!(read, 0)`
and the `gone` assertion are untouched.
Deviations: one file, no production code — the flake was in the tests. The two tests' duplicated
poll-and-assert became one `assert_reindexed` helper. The feature-gated
`watchman_sees_daemon_edit_within_1s_reading_nothing` was left alone: it is not in the Do, and it
only runs with `graph-watchman` and a `watchman` binary on PATH.

Goal: the index follows the working tree without a tool call paying for the walk. Plan: `plan.md` P8d.
