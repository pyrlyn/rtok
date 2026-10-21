**T10.7 `setup --remove` strips MCP** · T10.1 · `src/cli.rs`, `src/setup/claude.rs`, `src/setup/cursor.rs`
Do: `setup claude/cursor --remove` also removes `mcpServers.rtok` via the shared `unregister_stdio_mcp` helper (foreign servers kept); cursor keeps unlinking the plugin, and `--dry-run --remove` previews without touching the FS.
Check: unit `unregister_strips_only_rtok_and_keeps_foreign` (both hosts); temp-HOME apply (`--mcp` / `--yes`) → `--remove` → second `--remove` is `no changes`, foreign entries kept; `just check` green.
Complexity: 1/5 — two call sites plus one shared helper, no new flags, no config keys.
Status: done 2026-09-09
Check result: Absorbed by T10.9 — `rtok agent remove <host>` strips `mcpServers.rtok` through the shared `unregister_stdio_mcp` for both hosts, which is this Do in full (recorded under done.md T10.9). No separate implementation; counted ✅ so the plan no longer carries a superseded row.
Model: -

Model: -
