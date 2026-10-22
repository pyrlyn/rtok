Cloud review finding (2026-10-08): `crates/rtok-mcp/src/ops.rs` carried `runs_rtok`, a weaker copy of `rtok_agent_sdk::runs_bin`: it matched only `rtok`, `*/rtok` and `*\rtok.exe`, so `rtok.exe`, `RTOK.EXE` and the running binary's own path were "not rtok's". Deleted it; `not_removable` calls `rtok_agent_sdk::runs_bin`. The binary check (`is_rtok_bin`) lives in the `rtok` crate, which `rtok-mcp` cannot depend on, so `ops::apply` takes it as `is_bin: fn(&str) -> bool`, the same shape `judge_owned` and `unregister_owned` already use. `rtok-mcp` gains the path dependency `rtok-agent-sdk`.

Callers: none outside `ops.rs` tests (`apply` is unused until T277 wires hosts); the tests pass a test-local `is_rtok_bin`. Their expectations did not change.

Check: grep for `runs_rtok` over the repo found only `ops.rs`; `just fmt-check`, `just lint`, `cargo nextest run -p rtok-mcp -p rtok-agent-sdk` and `-p rtok -E "test(plan)"`.
