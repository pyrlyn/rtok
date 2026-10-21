Found 2026-10-11: Codex agents in `apps/cox` called `mcp__rtok__read` on `/Users/listepo/.agents/skills/worktrees/SKILL.md` and `/Users/listepo/.codex/RTK.md` (included by `~/.codex/AGENTS.md`); both were refused with `path outside cwd` (`src/plugins/read/mod.rs`, also twice in `~/.rtok/errors.log`), and the agent retried with native tools. The refusal named no way out.

Done when: the refusal keeps its `path outside cwd: <path>` prefix and adds that the path is outside this project and its worktrees, so the agent should read it with the host's own file tool. The creator chose this over serving host skill directories (2026-10-11): the boundary stays as it is. Any path outside the project is still refused.

Result: `resolve_with` appends `(outside this project and its worktrees; read it with the host's own file tool)`. Every caller that matches the `path outside cwd` prefix is unchanged.

Check: `a_refusal_points_to_the_host_s_own_file_tool` failed before the change (no hint) and passes after; `plugins::read` 90/90; `just check` green: 3328 passed, 8 skipped.

Status: done 2026-10-11
Model: Claude Code / claude-opus-5-5
