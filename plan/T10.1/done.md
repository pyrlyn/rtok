**T10.1 Cursor** · T2.1 · `src/setup/cursor.rs`
Do: write `~/.cursor/hooks.json` entries (beforeShellExecution → `rtok hook PreToolUse --host cursor` mapping fields) and MCP registration. Field mapping documented in code.
Check: fixture Cursor payload → wrapped command JSON.
Status: done 2026-09-02 · Check: `cursor_payload_wraps_command` — Cursor `command: ls -la` becomes `updatedInput.command` containing `rtok run -- ls -la`; `dry_run_then_apply_is_idempotent` writes `hooks.json` with `rtok hook PreToolUse --host cursor`. `make check` green. Deviation: also `cli.rs` (`--host` → `hook.host`, `setup cursor`), `hooks/mod.rs`+`types.rs` (`adapt_cursor`), `setup/mod.rs`, `claude.rs` (`read_settings`/`backup`/`register_stdio_mcp` shared).
