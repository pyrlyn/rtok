Found 2026-09-25: the Claude plugin serves rtok's MCP, yet `claude_desktop_config.json` still holds `mcpServers.rtok`, so the desktop Code tab lists every rtok tool twice (`mcp__rtok__*` and `mcp__plugin_rtok_rtok__*`). `doctor` reports the duplicate (T171) and `rtok agents install claude` should strip it (T244), but the dry run printed `? mcpServers.rtok … (changed by you; remove asks)`: rtok writes `{"type":"stdio","command":…,"args":["mcp"]}`, and the file holds the entry without `type` (Claude.app rewrites the file). `rtok_agent_sdk::unregister_owned` compared the two literally, so without `--yes` or a terminal the duplicate stayed.

Check: the new test passes; `rtok agents install claude --desktop --dry-run --no-restart` on this machine prints a removal instead of `? … changed by you`; `just check`.

Result: `without_default_type` in `crates/rtok-agent-sdk/src/lib.rs` drops a top-level `"type": "stdio"` before the ownership comparison in `unregister_owned` and the no-change check in `register_server` (the same literal comparison: a re-install over a type-less entry now reports no change instead of rewriting it). Test `unregister_owned_matches_an_entry_missing_the_default_type`: a type-less entry is removed, one with changed `args` is still kept. The dry run on the real config now prints `- mcpServers.rtok`. `rtok-agent-sdk` 35/35, `agents_install` / `agent_remove` / `singleton` 32/32, workspace clippy clean.

Status: done 2026-09-25
Model: Claude Code / claude-opus-5-5 (code by claude-sonnet-5, reviewed)
