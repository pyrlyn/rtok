Done means: `skills/` tells the agent on Codex, Grok Build, MiMo, omp and Antigravity to call `worktree_adopt` when it finds itself in a host-made worktree.

Check: the skill's gate tests.

Result (2026-10-03, Claude Code / sonnet-5): `skills/worktrees/SKILL.md` names the hosts whose own worktrees an agent binds (Cursor, Codex, Kilo, Devin, Grok Build, MiMo, omp, Antigravity) with MCP `worktree_adopt` or `rtok worktree adopt --task`, and the skill's list line mentions `origin`. The skill body had to stay under its 2048-byte limit, so the surrounding prose was tightened. The command-and-flag gate in `tests/skill.rs` now requires `rtok worktree adopt`.
