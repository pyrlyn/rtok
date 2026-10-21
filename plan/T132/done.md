`research.md` §17.3(4). Make the cheap path the default one: `plugins/claude/agents/rtok-scout.md` with `model: haiku`, `tools` limited to the rtok MCP `read`, `search`, `outline`, `explore`, `expand`, and a short system prompt — ranged reads only, never a whole file over the outline threshold, answer with `path:line` citations and no file dumps. Verify the plugin `agents/` directory format against the current Claude Code docs first and add the link to the `## Docs` list in `plugins/claude/README.md`.
Check: `rtok agents install claude` offers the agent file and removal takes it away (host matrix e2e); `tests/host_docs.rs` and `tests/agents_doc.rs` (`RTOK_BLESS=1`) green; T128's per-`agentType` split is the measurement — record `rtok-scout` vs `Explore`/`general-purpose` read bytes per sub-agent in `research.md` §17 after a dated window.
Progress (2026-09-24): `plugins/claude/agents/rtok-scout.md` ships with the plugin (`model: haiku`, the five tools under the plugin-scoped names `mcp__plugin_rtok_rtok__<tool>`); unit test on the frontmatter, install/remove e2e in `tests/claude_plugin.rs`. Left: the dated `rtok-scout` vs `Explore`/`general-purpose` read-bytes row in `research.md` §17 once a window of sessions has run with it.

Execution plan (2026-10-12): read the T128 per-`agentType` sub-agent split from this machine's store over the window since 2026-09-24 and write one dated `research.md` §17 row (`rtok-scout` vs `Explore`/`general-purpose` read bytes per sub-agent, with the command); no product code.

Result: `research.md` §17.4 has the dated row (2026-10-12, `rtok stats --since 18d --json`, 77 sessions since 2026-09-24): per sub-agent tool-result bytes are 53,342 for `rtok:rtok-scout` (n = 2), 140,390 for Explore (42) and 184,206 for general-purpose (1,057). n = 2 is too small to compare, and T128's read column counts native Read only, so the scout's MCP reads show as 0; the scout stays as shipped.

Check: The row cites the command, window, counts and caveats. Measured by Claude Code / claude-haiku-5-5.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
