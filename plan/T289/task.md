---
status: in progress
priority: P2
complexity: 4
readiness: 90%
agent: Claude Code / claude-opus-5-5
---

# T289. Worktrees the host creates join rtok: `rtok worktree adopt` and the post-create hooks

Depends on T285, T286. Only Claude Code can redirect worktree creation (T159). Cursor (`.cursor/worktrees.json` `setup-worktree*`), Kilo (`.kilo/setup-script`) and Devin/Windsurf (`post_setup_worktree`) only run a script after they create a worktree in their own pool (`research.md` §26). For worktrees to behave the same on every host, those must still get an owner, an agent id and rtok's remove / gc / clean.

Plan:
1. Worktree `_worktrees/rtok-T289`.
2. `rtok worktree adopt [<path>] [--task <id>] [--agent]` and MCP `worktree_adopt`: lock a host-made worktree with the v2 reason (T285), record the claim and `source: <host>`; the directory stays where the host put it. First confirm on each host whether a locked worktree breaks the host's own eviction (Cursor's cap of 25, Devin/Windsurf LRU); where it does, adopt records the claim in the store only, with no git lock, and the card says so.
3. Wire the post-create scripts: `rtok agents install <host> --project` writes rtok's entry into the project file (`.cursor/worktrees.json`, `.kilo/setup-script`, Devin/Windsurf's hook config), our entry only, the rest byte-for-byte (host-config rule); removal takes it out.
4. Hosts with native worktrees and no hook (Codex, Grok Build, MiMo, omp, Antigravity): the skill tells the agent to call `worktree_adopt` when it finds itself in a host-made worktree.
5. `rtok worktree list` already shows every registered worktree of the repo (git knows them wherever they are); add `source` (`rtok`, `claude`, `cursor`, …) from the path pool.

Check: adopt e2e in a scratch repo with a worktree under a fake `~/.cursor/worktrees/`; install/remove e2e per host writing only our entry; list shows `source`; `just check`.

Execution (2026-10-03, Claude Code / sonnet-5): split into four PRs, each at most about 300 LOC, stacked on T288 step 4. Design decisions the card left open:
- `adopt` is `claim` with three differences: the path defaults to the caller's worktree (cwd), `--task` names the task when the branch cannot (a detached HEAD such as Codex's `thread-N`; the directory name is the last fallback), and a worktree in a pool whose host evicts by itself (Cursor, Windsurf/Devin, Codex) gets **no git lock**, only the claim row. Reason: the card says to confirm per host whether a locked worktree breaks the host's eviction, and that needs a live run on each host (the creator's probe, like T281); until it is confirmed, a lock could stop Cursor's cap of 25 from evicting, so the safe choice is the store-only claim. `worktree list` and `gc` already read an unlocked worktree's claim row (T285), so a live agent's adopted worktree is still never collected. Flip the pool table once a host is confirmed.
- The origin of a worktree is derived from its path (`~/.cursor/worktrees/`, `~/.windsurf/worktrees/`, `<repo>/.claude/worktrees/`, `<repo>/.kilo/worktrees/`, `$CODEX_HOME/worktrees`, `~/conductor/workspaces/`; else `rtok` under `[worktree] root`, else `other`), so no migration. The `worktree list` table already has a `source` column (source bytes), so the new field is named `origin` in the table and in `--json`.

Progress (2026-10-21, `research.md` §26 "Git lock against a host's own eviction"): T289.1–T289.5 are in `done.md`, so steps 2–5 and the Check's e2e parts are shipped. Still open: step 2's per-host confirmation. Git's documented lock rules (`remove` refuses a lock without `-f -f`; `prune` never drops a locked record) mean a lock in an evicting pool either blocks the sweep or does nothing. The store-only claim in `origin::evicts` is therefore never worse, and it gives up protection only on a host whose sweep honours locks. No host was run. Agents never run one (T280), and this session's permissions blocked scratch scripts, `gh search` and web search. A re-run protocol is written there. Left: the creator's live run on Cursor, Windsurf/Devin and Codex; then flip `evicts` per host, or keep it, and close the card.
