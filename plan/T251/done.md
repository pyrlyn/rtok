Found 2026-09-24: `tests/latency.rs` (`hook_returns_despite_exclusive_lock`), `tests/graph_bench.rs` (`home`) and `tests/lossless_roundtrip.rs` (`cmd`) built `Config::default()` and overrode only `core.db_path` and `core.archive_dir`, so `log.path` stayed `~/.rtok/logs/rtok.log`. Any log line they emitted — the hook's timing-dependent `note_slow` warn past `[hook] max_ms` — landed in a literal `./~/.rtok/logs/rtok.log` under the crate root.

Plan: build each config with `rtok::testutil::config_in(dir)` (as `tests/otel.rs` already does) and drop the redundant path overrides. Test-only; the unit tests got the same migration separately.

Check: `rm -rf ./~ && mise exec -- cargo nextest run --workspace -E 'kind(test)'` leaves no `./~`; `just check` green.

Result: the three sites use `config_in`; `graph_bench.rs` loses its now-unused `Config` import. `cargo nextest run --workspace -E 'kind(test)'`: 559/559 passed, no `./~` afterwards.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5

`codex_plugin::hook_commands_resolve_path_then_ketch_then_exit_open` (T250.1) failed ubuntu CI twice in a row on PR #300 with `BrokenPipe` at `stdin.write_all(b"{}").unwrap()`: with no `rtok` found the hook fails open and exits before reading stdin, so the write races the exit. Main auto-reverts on a red `check`, so the flake could throw out unrelated merges.

Result: the codex test and the two same-shaped writes in `tests/claude_plugin.rs` drop the write's result, as `tests/plugins_e2e.rs` already did; the exit status and stdout asserts still judge the hook.

Check: `cargo nextest --test claude_plugin --test codex_plugin` 11/11; `just check` green.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
