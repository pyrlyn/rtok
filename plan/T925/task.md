---
status: in progress
priority: P1
complexity: 3
readiness: 10%
agent: Claude Code / claude-sonnet-5-5
---

# T925. Cursor plugin link into the versioned ketch store dangles after an upgrade; doctor stays green

`~/.cursor/plugins/local/rtok` is a symlink to `~/.ketch/store/rtok/v0.15.1/plugins/cursor`
(made 2026-10-05). ketch now holds only `v0.17.0-alpha.2`, so the link dangles: Cursor loads no
rtok plugin, hooks, skills or rules, and `~/.cursor/hooks.json` has no rtok entry. Only the MCP
entry in `~/.cursor/mcp.json` still works. Doctor and `agents info` did not report it.
Related: T278 (MCP state in `agents info`, in progress).

Done when: the Cursor install does not point into a versioned store path that an upgrade
removes (copy, or a stable link re-pointed on upgrade), and doctor flags a dangling or stale
plugin link for every host that installs by link.

Check: an install test that removes the old store version and still finds the plugin; a
doctor test with a dangling link; `just check`.

Execution plan:
1. `rtok-agent-sdk`: on Unix `PluginLink` installs an owned copy (not a symlink) when the source
   sits under a versioned ketch store directory (`store/<pkg>/v<ver>/`); an existing link into
   one counts as not up to date, so the next `agents install` replaces it. Add
   `PluginLink::health()` (dangling / stale / current / foreign).
2. `src/agents/plugin.rs`: one registry of every link-installed host plugin; `src/doctor/hooks.rs`
   reports a dangling or stale link as `stale-plugin` for each.
3. Tests: install from a fake ketch store, remove the old version, plugin still there; doctor test
   with a dangling link; `just check`.
