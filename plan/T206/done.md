Found 2026-09-22 in the surfaces pass: every 2 s tick per connection runs `model::snapshot` synchronously in `socket_loop` with `DashState::cfg` locked across the frame (`src/web/mod.rs:251-281`), and the snapshot includes `doctor_for_snapshot` (spawning MCP probes) and `stats_skills`' whole-transcript parse (I-87 / T135's ~36 s CPU per TTL miss) plus blocking `fetch_live` HTTP in the tick (`src/web/model.rs:1246-1251`). `health()` and `inbound()` take the same mutex, so one cache-miss freeze blocks every socket and `/health` (used by demon/doctor) — the process reads as hung. T113 fixed only the TUI thread; the `rtok web` async/lock defect itself is new.

Plan: clone/`Arc` the `Config`, build each snapshot in `spawn_blocking` without holding the lock, and coalesce concurrent ticks into one in-flight build shared by all sockets. (The transcript-parse burn itself is T135.)

Execution plan:
- `DashState` gains a `build: Mutex<BuildSlot>` (`Idle` / `InFlight(watch::Receiver<Option<String>>)`) and a pluggable `build_fn: Arc<dyn Fn(&Config) -> String + Send + Sync>` (production default `frame`; `DashState::with_builder` lets tests substitute a barrier-blocked builder).
- New `DashState::snapshot_shared()`: one lock acquisition atomically either joins an in-flight build's `watch` channel or claims the builder role (no TOCTOU between two ticks landing at once); the claimed build clones `Config` under `cfg`'s mutex (released immediately after) and runs in `tokio::task::spawn_blocking`, so no lock is held across the build and `health()`/`inbound()` stay unblocked.
- `socket_loop` calls `state.snapshot_shared().await` instead of building inline while holding `cfg`'s mutex.
- `inbound()`'s config edits are untouched: each tick still clones the current `cfg` when it claims the builder role, so a build started just before an edit may serve one stale tick, and the next tick reflects it.
- New test `health_answers_during_a_snapshot_build` in `tests/web.rs`: a builder blocked on a `std::sync::Barrier` stands in for a slow snapshot; several WS clients tick at once (coalescing onto the one in-flight build) and `/health` must answer within a generous bound while the build is stuck.

Check: `health_answers_during_a_snapshot_build` — busy fixture store + several WS clients, `/health` p95 < 250 ms while ticks run; `ws_set_accepts_plugin_enabled` green; `just test` green.

Result: `socket_loop` calls `DashState::snapshot_shared`: the `Config` mutex is held only to clone it, the frame is built in `spawn_blocking`, and ticks that land during a build share its result through a `tokio::sync::watch` slot. If a builder is dropped or panics, the next waiter claims the build instead of hanging. `/health` and `inbound()` no longer wait on a snapshot. Test `health_answers_during_a_snapshot_build` blocks a substituted builder on a barrier with three WS clients and asserts `/health` answers; `just check` green.

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
