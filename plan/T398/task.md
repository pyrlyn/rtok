---
status: todo
priority: P3
complexity: 1
readiness: 40%
agent:
---

# T398. Probe editors on a `worktree.useRelativePaths` worktree

From `research.md` §18.2 (T157): every non-interactive reader opens a relative-link worktree, but VS Code, Zed, Cursor and lazygit are untested, so the setting stays opt-in. It is the setting that would have prevented the 18 GB orphaned `graph-perf` worktree.

Done means: one dated row per editor in §18.2. If all pass, the `worktrees` skill and `AGENTS.md` gain the setting (T157's Check); if any fails, the failure is recorded and the setting stays off.

Check: the §18.2 table has four dated rows.

Progress (2026-10-21, `research.md` §18.2): an agent session could not launch any of the four (sandbox refused every editor command), so each row reads "not run" with that date, and §18.2 now holds the manual protocol plus a lead: this machine's shell resolves `/usr/bin/git` first, and a git older than 2.48 refuses `extensions.relativeWorktrees`. Left: the creator (or an unsandboxed session) runs the protocol and turns the four rows into pass or fail; the setting stays off until then.
