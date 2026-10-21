Plan: `HookInput::adapt_cline(event)` in `src/hooks/types.rs` beside `adapt_cursor` / `adapt_copilot` / `adapt_devin`; a `cline` arm in `dispatch_owned_strict` plus `cline_output` in `src/hooks/mod.rs`; `--host` list grows by `cline` (`config/default.toml`, `docs/config.md`, `src/cli.rs` help line, trycmd snapshots).

Do (Muse Spark / rtok): `adapt_cline` lifts `hookName` / `taskId` / `workspaceRoots` / `tool_call` / `tool_result` to Claude fields; `run_commands` single-command → `Bash` + `command`, `read_files` → `Read`, multi-entry commands pass through untouched; lifecycle (`agent_start`, `agent_resume`, `prompt_submit`, `agent_end`, `agent_error`, `agent_abort`, `session_shutdown`) maps to SessionStart / UserPromptSubmit / SessionEnd / no-op. `cline_output` prints `overrideInput: {commands: [..]}` for rewrites, `context` for injections, `cancel: true` + `errorMessage` for denies, `{}` otherwise. Fail open: garbage stdin prints `{}` and exits 0.

Status: done 2026-09-22
Check result: `hooks::types::tests::cline_maps_tool_call_result_and_lifecycle`, `hooks::tests::cline_output_shapes_override_context_block_and_empty`, `hooks::tests::cline_pre_tool_use_rewrites_single_command_and_fails_open` green; `cargo test --lib` 908 passed; fmt + clippy clean.
Model: Muse Spark / rtok
