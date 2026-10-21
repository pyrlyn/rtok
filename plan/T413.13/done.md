Trae IDE (ByteDance). MCP; check whether its config mirrors the VS Code layout `src/agents/vscode/` already handles.

Check: the T413 per-host check (step 5) for this host.

Plan: Trae docs for the MCP file. Reuse the VS Code helper only if the key and path match; otherwise a thin host of its own.

Finding (2026-10-12, `research.md` §32.13): Trae's docs name only a project `.trae/mcp.json`, which is the project's, and a user-level `mcp.json` behind the app's Raw Config button with no path. No installer is written until a documented user-level path exists.

Done: closed without code, like T413.10. Rule 1 of T413 forbids guessing the path, so there is nothing to write. The claim from 2026-10-12 had stayed at 0% after #1030 recorded the finding, and the closing session on 2026-10-21 had no web access to re-read the docs page, so §32.13 is still the latest check. Trae is not installed on this machine. The installer moved to `roadmap.md` "Agent hosts" with its re-open condition: a vendor page (or a Trae install to inspect) naming the user-level `mcp.json` path. The VS Code helper is reused only if that file uses VS Code's key and layout.

Check: nothing to run; docs-only change. `plan.md` table, cards and `todo.md` stay in sync.

Status: done 2026-10-21
Model: Claude Code / claude-opus-5-5
