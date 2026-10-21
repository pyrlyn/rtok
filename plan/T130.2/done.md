T130.1 (`done.md`) landed the mechanism — `SubagentStart` on the `Plugin` trait, the hook dispatch, config, and `memory::handoff::build_brief` shared with the `handoff` MCP tool — but nothing yet installs a `SubagentStart` matcher for real users, so the feature is inert until this lands. Needs: (1) `rtok agents install claude` registers `SubagentStart`, and `plugins/claude/hooks/hooks.json` carries the same entry (the plugin is the installer's hooks, T114); (2) `docs/agents.md` reblessed (`tests/agents_doc.rs` with `RTOK_BLESS=1`) and `tests/host_docs.rs` green. Split on claiming (2026-09-24): the outline line ranges (the original part 3) moved to T130.3.

Scope: Claude only. `ENTRIES` in `src/agents/claude/mod.rs` is shared wholesale with Kimi (`src/agents/kimi/mod.rs`), which has not been cleared for `SubagentStart`; whether Kimi and the other Claude-compatible hosts (Gemini, Grok, Copilot, …) get it too stays the creator's call, raised in the PR.

Plan:
1. `src/agents/claude/mod.rs`: `CLAUDE_ENTRIES` = `ENTRIES` + `("SubagentStart", "")`, built in a const block; the settings installer (insert and strip) and the plugin-tree test use it; Kimi keeps `ENTRIES`.
2. `plugins/claude/hooks/hooks.json`: the `SubagentStart` entry, same resolver line as the other events.
3. Bless `docs/agents.md`; `agents_install` e2e and the claude unit tests show the new matcher; `just check`.

Result: `CLAUDE_ENTRIES` (`ENTRIES` + `SubagentStart`, const-built) drives the settings installer's insert/strip and the plugin-tree test; `plugins/claude/hooks/hooks.json` carries the matching `SubagentStart` entry; both READMEs name it. Kimi keeps `ENTRIES` unchanged. `docs/agents.md` needed no change (the host table lists modules, not events). The hook stays inert until `[plugins.memory] spawn_brief = true` (T131 measures it).

Check: `agents_install` matrix e2e shows the new `SubagentStart` matcher for the `claude` host; `tests/agents_doc.rs` and `tests/host_docs.rs` green; `just check` green.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
