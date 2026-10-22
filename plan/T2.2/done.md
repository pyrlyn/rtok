**T2.2 latency harness** · T2.1 · `tests/latency.rs`
Do: spawn `rtok hook PreToolUse` 200× with the fixture; assert p95 < 10 ms on this machine (release build).
Check: `cargo test --release latency` passes.
Status: done 2026-09-02 · Check: `cargo test --release latency` green (p95 < 10 ms). Debug `make check` skips the assertion. Deviation: dispatcher no longer writes a `plugin_run` child row per enabled plugin on every event — that was ~27 SQLite inserts and blew p95; plugins record their own `Measurement` when they act. Parent `calls` + `call_io` remain.
