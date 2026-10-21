`tests/extra_cover.rs` checks bad and empty stdin for the default host only. One `rstest` matrix in `tests/hook_fail_open.rs`: every value `[hook]` host accepts × every hook event × stdin {empty, garbage, truncated JSON, non-UTF-8, 1 MiB}. Done when each case exits 0, prints the host's no-op reply, and never rewrites the input.

Check: `just test` green; one case per host × event × stdin in the nextest list.

Do (Command Code / claude-fable-5, 2026-09-22): one `#[rstest]` matrix in the new `tests/hook_fail_open.rs`. Hosts: every value `[hook]` host takes its own envelope path for — the `--host` help's `claude | cursor | copilot | devin` plus grok (T98's `GROK_HOOK_EVENT` envelope). Events: the eight names `hooks::dispatch` and the installers know — `SessionStart`, `UserPromptSubmit`, `PreToolUse`, `PostToolUse`, `PreCompact`, `PostCompact`, `SessionEnd`, `Stop`. Stdin kinds: empty, garbage, truncated JSON, non-UTF-8, 1 MiB of garbage — 200 cases, each spawning the binary with `--host <host>` under its own `RTOK_HOME`. Every case exits 0 and prints exactly `{}` — the host's no-op reply, never a rewrite (a parse failure fails open before any host transform, so the reply is the same empty object on every envelope; the exact-equality assert is what rules out an `updatedInput` / `hookSpecificOutput` leak).

Check result (2026-09-22): `--test hook_fail_open` 200/200 green in ~2 s, every case named in the nextest list after its own combination (e.g. `host_5___grok__::event_8___Stop__::stdin_5___1mib__`); `fmt --check` and `clippy -D warnings` green; full `cargo nextest run` — 952 passed / 1 failed / 4 skipped, the one failure being the pre-existing machine-state `agents_real_config windsurf_keeps_the_real_mcp_config_json` (T166, fails on clean `main` too).

Deviations: none. "Never rewrites the input" is asserted as stdout being exactly `{}` — any rewrite would carry `hookSpecificOutput`, `overrideInput` or the host's flat decision keys and fail the equality.

Status: done 2026-09-22
Model: Command Code / claude-fable-5
