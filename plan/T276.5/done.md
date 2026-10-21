Split from T276 (migration plan step 3, 2026-10-11, D16): `graph/lsp.rs`, `graph/mod.rs`, `graph/watch.rs` (`Capture`, long-lived LSP children through `.spawn()`), `demon.rs` (`Detached` / `Inherit` / `Capture`), `log.rs` (`Inherit`), `otel/export.rs`, `hooks/mod.rs`, `bin/rtok-hook.rs`, `mcp/wrap.rs` (`Detached` / `Stream`, always hidden). Depends on T276.1.

Check: hook latency budget unchanged (fail open, 10 ms); `just check`.

Result: graph (LSP, `rustup which`, git, `cat-file --batch`), `demon.rs`, `log.rs`, `otel/export.rs`, `hooks/mod.rs`, `mcp/wrap.rs` and `bin/rtok-hook.rs` run children through `ProgressRunner`, which gains spawn-only `stdout()`/`stderr()` overrides. `graph/watch.rs` spawns nothing. `rtok-hook` now links `rtok_sys::proc` (debug binary +7%).

Check: `spawn_takes_explicit_stdout_and_stderr_over_the_mode_defaults`; demon, `hook_fail_open`, `hook_resident`, `hook_spawn_brief`, `latency`, `mcp_wrap`, `graph_lsp_gate`, otel, logs suites green.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
