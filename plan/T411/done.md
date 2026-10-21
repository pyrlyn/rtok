Why: the creator asked (2026-10-04) that for worktree work an agent learns where it stands from `rtok worktree whoami` instead of `rtok agents whoami`, and that the `worktrees` skill say so and drop what rtok already enforces. `rtok agents whoami` stays for the agent registry.

Done means: `rtok worktree whoami [--json]` prints the caller's agent (`RTOK_AGENT_ID`, short id and host), the `[worktree] root`, the linked worktree the cwd is in, and the repository's worktrees bound to the agent by lock or by claim row; no size scans; outside a repository only agent and root; behind the `[worktree] enabled` gate. The skill starts with it and keeps only create, finish and never.

Check: `just check`.

Result: `src/worktree/whoami.rs`, `WorktreeCmd::Whoami` in `src/cli.rs`; gates in `tests/surface_parity.rs` (EXEMPT, JSON reader), `docs/config.md`, `README.md`, `help-subcommands.trycmd` and the completion goldens; `tests/worktree.rs` `whoami_shows_the_root_and_only_the_caller_s_worktrees` (a locked and a claimed worktree of the caller, not another agent's claim; `here` follows the cwd; text form). `skills/worktrees/SKILL.md` went from 342 to 206 words; the creator's `~/.claude/skills/worktrees/SKILL.md` carries the same text. `just check`: 2440 passed.
