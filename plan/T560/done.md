Creator request (2026-10-11): rtok plugin support for Tencent WorkBuddy. WorkBuddy shares CodeBuddy Code's plugin system, so this is one host `codebuddy` with two variants, CodeBuddy Code CLI and WorkBuddy Desktop (the `devin` CLI + Desktop shape), served by one plugin tree under the D21 singleton.

Facts (primary unless marked; checked 2026-10-11):
- Plugin manifest dirs: `.codebuddy-plugin/` (priority), `.workbuddy-plugin/`, `.claude-plugin/`; hooks in `hooks/hooks.json`, MCP in `.mcp.json`; `${CODEBUDDY_PLUGIN_ROOT}`, alias `${CLAUDE_PLUGIN_ROOT}`; cache `~/.codebuddy/plugins/cache/<marketplace>/<plugin>/<version>` — https://www.codebuddy.ai/docs/cli/plugins-reference
- CLI: `codebuddy plugin marketplace add <local dir> [--name <name>]`, `codebuddy plugin install <plugin>@<marketplace> [-s user]`, `codebuddy plugin uninstall <plugin> -y`, `codebuddy plugin list --json`; install records `installed_plugins.json` — same page and https://www.codebuddy.ai/docs/cli/plugin-marketplaces
- Hooks: settings `~/.codebuddy/settings.json` `hooks` key; stdin `session_id`, `transcript_path`, `cwd`, `hook_event_name`, `tool_name`, `tool_input`, `tool_response`; PreToolUse output `hookSpecificOutput.{permissionDecision, permissionDecisionReason, modifiedInput}` (no `updatedInput`); PostToolUse `additionalContext`; tools `Bash`, `Read`, `Edit`, `Write`, `Grep`, `Glob` — https://www.codebuddy.ai/docs/cli/hooks
- MCP: user file is the first existing of `~/.codebuddy/.mcp.json`, `~/.codebuddy/mcp.json`, `~/.codebuddy.json` (created as `.mcp.json`), `mcpServers.<name>.{type, command, args}` — https://www.codebuddy.ai/docs/cli/mcp
- `CODEBUDDY_CONFIG_DIR` moves the config and data dir — https://www.codebuddy.ai/docs/cli/env-vars
- **unverified** (https://github.com/lorelum/lorelum/issues/205, WorkBuddy 5.5.6 on Windows): the WorkBuddy app embeds the CodeBuddy Code CLI and keeps its registry in `~/.workbuddy`, so `CODEBUDDY_CONFIG_DIR=~/.workbuddy codebuddy plugin …` installs into WorkBuddy. WorkBuddy's own docs (https://www.codebuddy.ai/docs/workbuddy/Changelog, 5.7.6) do not mention plugins or hooks.

Confirmed by an actual-source round-trip probe: [encode_cell](src/plugins/toon/mod.rs#L188) correctly quotes a string containing a comma, but the test-only [decode](src/plugins/toon/mod.rs#L225) unconditionally splits each row on every comma. The accepted table `[{"a":"x,y","b":1,"c":true}]` encodes to a three-column row and decodes to None because the oracle sees four cells. The round-trip cases at line 375 omit commas. T468 intentionally kept this decoder as the losslessness oracle after removing it from release code; this task repairs that verification path and makes no production-decoder/data-loss claim.

Execution plan:

1. Add the quoted-comma table to the round-trip cases and verify the current oracle fails on it.
2. Parse row cell boundaries with quote/escape awareness in the test decoder, matching the existing encoder's grammar; do not weaken the encoder or reject valid input to satisfy a broken oracle.
3. Add generated round-trip coverage combining commas, quotes, backslashes, control characters, empty strings and scalar-looking strings, and check declared row/column counts.

Check: decode(encode(table)) equals the original for all accepted generated fixtures, valid comma cells pass, malformed rows are rejected, and `just check` passes. This is a P3 test-quality defect.

Result: The TOON test decoder in `src/plugins/toon/mod.rs` splits rows quote-aware, rejects malformed rows and checks the declared row count, so a quoted comma cell decodes instead of becoming None.

Check: `round_trip_values`, `generated_cells_round_trip_with_declared_counts`, `malformed_rows_are_rejected`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
