**T1.4 `rtok doctor`** · T0.2 · `src/doctor.rs`
Do: report: hooks in `~/.claude/settings.json` by event and by tool (count 81 today); MCP servers and their tool counts with estimated description tokens (read `~/.claude.json` / `.mcp.json`; count via T0.5); `ANTHROPIC_BASE_URL` chain (probe each hop's `/health` or TCP); whether MCP tool search is enabled (docs: setting a base URL disables it by default — flag it); `BASH_MAX_OUTPUT_LENGTH`; `autoCompactWindow`.
Check: `rtok doctor` on this machine prints 81 hooks, lists lean-ctx (78 tools), and the 8788→8787 chain.
Status: done 2026-09-02 · Check: `rtok doctor` prints hooks 81 and proxy 8788→8787. Deviation: lean-ctx tools/list is 12 (v3.10.0), not the 78 recorded in research.md at plan time.
