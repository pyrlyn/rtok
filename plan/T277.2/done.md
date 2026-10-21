Epic PR 3, first half: the strict-JSON hosts T275 listed as affected. Each keeps only its `McpSpec` value (`mcp_spec`); its `register_mcp`, `unregister_mcp` and `mcp_entry` are gone. `agents::mcp::json_spec` builds a strict-JSON host spec (launch command, `--host <id>`, entry shape), and Claude's `mcp_spec_at` uses it too. `agents::mcp::sync` maps `agents install|update|remove` onto `ops::Mode` behind `[setup] mcp`; Cursor, Kimi, VS Code and the Claude Desktop branch call it. Copilot and Gemini keep `d21_plugin_apply` and pass closures over `mcp::install` / `mcp::remove`, because Qwen and CodeBuddy (JSONC, not migrated) still pass their own fn items. The crate gains `EntryShape::LocalAllTools` for Copilot's `{type: "local", ..., tools: ["*"]}`.

Behaviour is unchanged except one case inherited from T277.1: a `mcpServers` / `servers` value that is not an object is an error naming the file, not replaced by `{}`. ZCode (nested `mcp.servers`), Codex and Grok (TOML) are the next subtask.

Check: crate test `every_entry_shape_writes_its_documented_keys`; every host's existing lifecycle (T275), legacy-upgrade (T283.2) and ownership (T246) tests pass with only their call sites changed; `just full-check`.

Result: `just full-check` on macOS against `c8ceaa2d`: fmt, clippy `-D warnings`, nextest 4101 passed, 8 skipped, pytest 43 passed, `build-min`, jscpd 1.79% lines; `just docs-check` green.
Status: done 2026-10-21
Model: Claude Code / claude-opus-5-5
