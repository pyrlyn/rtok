Creator request 2026-10-08: find out why a worktree is not always removed after its task merges, and fix it. Findings (2026-10-08, live repository and agent transcripts):

1. `rtok worktree remove` from an agent's Bash often has no caller: in the desktop app `CLAUDE_ENV_FILE` stays empty, so `RTOK_AGENT_ID` is unset, and one app process serving many sessions in one cwd leaves MCP `worktree_remove` unlinked. `remove::run` then refuses every lock (`is locked by …; not removed`, seen in 8+ transcripts); a v2 lock (with `agent <uuid>`) refuses even `--owner`. Agents give up or fall back to raw `git worktree remove`.
2. Sub-agents bind their worktrees to the parent agent, and `gc` keeps every worktree of a live agent. One orchestrator alive for a day held 13 worktrees, 8 of them merged and clean.
3. Nothing triggers removal after a merge; `gc` runs only by hand.

Done means: a worktree that is merged into the base, clean, and whose branch carries commits of its own outside the base (a squash-merged task branch, never a fresh branch still at the base) is removed by `rtok worktree remove` / MCP `worktree_remove` whoever holds its lock, and by `gc` even while its agent is live once it is idle past `--idle`. Dirty, unmerged, fresh and fast-forward-merged worktrees keep every guard they have today.

Plan:
1. `src/worktree/git.rs`: `has_own_commits(repo, base, rev)` — `git rev-list --count base..rev` > 0.
2. `src/worktree/mod.rs`: `Entry` gains `done: bool` (merged, clean, own commits), computed in `inventory_with` only for merged rows.
3. `src/worktree/remove.rs` `run`: a foreign or unknown lock no longer refuses a `done` worktree; the note says whose lock was opened.
4. `src/worktree/gc.rs` `decide`: a live agent's `done` worktree falls through to the idle check instead of `keep`.
5. Tests: unit cases in `gc.rs` `verdicts`; an e2e in `tests/worktree.rs` with a squash-merged branch under a foreign v2 lock (removed) and a fresh branch under the same lock (refused).
6. `just check`; dry-run `rtok worktree gc` on the live repository lists the 8 merged worktrees as `remove`.

Check: `cargo nextest run -E 'test(/worktree::gc::/) | binary(worktree)'`, `TRYCMD=overwrite` for `cli_trycmd` and `completions`, and `just check`; a dry-run `rtok worktree gc --idle 1h` on the live repository.

Result (2026-10-08, Claude Code / claude-opus-5-5): `Entry::done` marks a merged, clean worktree whose branch has commits outside the base. `remove` opens a foreign or unknown lock on it and says so in the note; `gc` removes it past a live agent or a foreign lock once idle past `--idle` (`Verdict::Finished`), and keeps the old reason inside the window. A fresh branch under a foreign lock is still refused. On the live repository, `gc --idle 1h` now plans to remove 7 of the orchestrator's 8 merged worktrees (the eighth was touched within the hour) and keeps every open PR's. Not fixed here: the desktop app leaves `RTOK_AGENT_ID` unset and MCP unlinked, so an agent still cannot name itself.
