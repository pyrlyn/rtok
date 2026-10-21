`~/.codex/config.toml` has `[mcp_servers.rtok] command = "rtok"`, and `~/.cursor/mcp.json` the
same. Codex desktop logged `MCP client for rtok failed to start: No such file or directory
(os error 2)` 14 times on 2026-10-18 (12:58–17:30 UTC). The plugin hooks already fall back
(`command -v rtok || $HOME/.ketch/bin/rtok`); the MCP entries do not. Related: T275 (MCP entry
removed on update).

Done when: the installers write an MCP command that resolves without the GUI app's PATH (an
absolute path to the installed binary, kept current on update), and doctor flags an MCP
command that does not resolve.

Check: install tests for Codex and Cursor asserting the written command is absolute and
exists; a doctor test with an unresolvable command; `just check`.

Result: `rtok_mcp_command()` (`src/agents/mod.rs`) names the `rtok` found on PATH, else `~/.ketch/bin/rtok` (ketch re-points that link on upgrade), else the old bare name; every MCP writer uses it (Codex, Cursor, Claude CLI, Gemini, Copilot, Devin, Kimi, OMP, VS Code, Windsurf, Zed, Grok, Reasonix, OpenCode/MiMo, Continue, Goose and the rest), hook lines keep `rtok_command()`. Codex repoints an older bare `command = "rtok"` and leaves a path the user chose; JSON hosts already rewrite on update. `rtok doctor` and `agents list` read an MCP entry as stale when its command is an absolute path to nothing, or a bare name while an absolute `rtok` exists. Windows is unchanged.

Model: Claude Code / claude-sonnet-5-5
