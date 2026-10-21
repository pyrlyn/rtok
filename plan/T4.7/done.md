**T4.7 register MCP** · T2.3, T4.1 · `src/setup/claude.rs`
Do: `rtok setup claude --mcp` adds `rtok` to `~/.claude.json` mcpServers (stdio, command `rtok mcp`), idempotent, backup.
Check: dry-run shows one server entry; second run "no changes".
Status: done 2026-09-02 · Check: dry-run `mcpServers.rtok: rtok mcp`; second apply `no changes`. `mcp_dry_run_then_apply_is_idempotent`. `make check` green. Deviation: also `src/cli.rs` (`--mcp` → `setup.mcp`).
