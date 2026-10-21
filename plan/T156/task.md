---
status: in progress
priority: P3
complexity: 3
readiness: 50%
agent: Claude Code / claude-opus-5-5
---

# T156. Probe: `WorktreeCreate`/`WorktreeRemove` hooks and reflink-seeded `target/`

No product code. Two open questions from `research.md` §18.3–18.4: (1) Claude Code's `WorktreeCreate`/`WorktreeRemove` hooks replace the default create/remove — can rtok own location, naming and the ownership record there, and what do the desktop app and sub-agent `isolation: worktree` actually send; (2) does seeding a new worktree's `target/` by reflink (`reflink-copy`, APFS `clonefile`) save build time and disk after a real task, or does cargo rewrite most of it anyway.

Plan: throwaway hook script (scratch, not committed) that logs the payloads for `claude --worktree`, a sub-agent worktree and the desktop app, and returns a path under `_worktrees/`. For (2): two fresh worktrees of this repo, one seeded with `cp -c -R target`, one cold; record wall time of `just check` and physical disk delta (`df`, not `du` — clones are double-counted) for each. Write the payloads, the numbers and the dated commands into `research.md` §18. `reflink-copy` is a new dependency: adopting it is a creator decision taken on those numbers, not part of this task.

Progress (2026-09-25, `research.md` §18.4 second data point): part (2) measured. Cold `just check` took 185 s and +6.28 GiB; seeded took 371 s and +3.35 GiB. The clone skipped every dependency rebuild (≈ 23 s saved), but T236's `dunnage` pass then compressed the cloned files (≈ 215 s). Parked as I-99, with no follow-up task. Lead (unmeasured, 2026-10-08): the installed `dunnage` 0.1.0 has its own `seed` and `worktree add` subcommands (`dunnage --help`), so seeding may not need `reflink-copy`. Part (1), the hook payloads from `claude --worktree`, a sub-agent worktree and the desktop app, is still open: it needs live sessions of the creator's.

Progress (2026-10-21, `research.md` §18.4, fourth attempt): nothing measured. 20 GiB were free against the 30 GiB floor, the load average was 11.7–31.5, and the agent session could not run a host. Two changes since the second data point are recorded: the pass is now `swarfr` (#622), and `just check` is selective (T420), so a re-run must time `just full-check`. A re-run protocol is written there that times `swarfr` on its own. Left: the creator's live hook run and the quiet-machine re-run.

Check: `research.md` §18 gains the hook payloads and a dated table (cold vs seeded: seconds, bytes); T159's card is corrected against the recorded payloads; seeding gets a follow-up task or an `ideas.md` entry from the numbers; no file under `src/` changes.

Execution (2026-09-27): (1) a probe kit in the session scratchpad (never committed), like T281, that logs `WorktreeCreate`/`WorktreeRemove` payloads and returns a `_worktrees/` path; the creator runs it with `claude --worktree`, a sub-agent `isolation: worktree` and the desktop app. The documented payload fields go into `research.md` §18.3 now, with sources. (2) Seeded (`cp -c -R target`) and cold worktrees of this repo: wall time of `just check` and physical disk delta (`df` before/after, not `du`). The cold run only happens with ≥ 30 GiB free; otherwise the row says so. Result: a dated row in `research.md` §18.4.
