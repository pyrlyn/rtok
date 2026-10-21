Same creator request as T246.1: `agents remove <host>` takes back only the MCP entry rtok wrote; an entry the user edited is asked about, one not running rtok is left alone.

Result: copilot, gemini, kimi, omp, vscode, windsurf and zcode each build their entry in one private `mcp_entry(cmd)` shared by `register_mcp` and `unregister_mcp`, and remove through `agents::unregister_ours` (T246.1). opencode, kilo and mimo share `register_local_mcp`, so `unregister_local_mcp` got the same check once in `agents/mod.rs`. zed (JSONC) and grok (TOML) and hiding the name-only `unregister_server` moved to T246.5 to keep this PR under 10 files.

Check: `windsurf_remove_asks_before_taking_an_edited_mcp_entry` (edited entry left without `--yes`, taken with it; a foreign `rtok` entry left even with `--yes`); `cargo nextest --test agent_remove --test agents_install --test singleton --lib agents` green; `just check` green.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
