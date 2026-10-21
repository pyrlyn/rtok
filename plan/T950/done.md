Bug. In `gc::decide` the `held` branch ran before `match entry.state`, so a `State::Stale` record (its directory is gone) under someone else's git lock got `keep("locked by …")` on every run and never reached `Verdict::DropRecord`. Seen: `_worktrees/completions-shared/apps/rtok-completions-shared` and `_worktrees/rtok-audit-20261018` (locked by OpenAI / GPT-6, 0 B, directories missing).

Choice: after `stale_lock`, not at once. Git's lock exists for a worktree on a volume that is not mounted, so a fresh lock on a missing directory may still be in use. The record has no mtime, so the lock reason's date is its age. A vanished record under a foreign lock is dropped once that date is older than `stale_lock`, or at once when the lock's agent has ended (T949). An undated or bare lock still keeps it. `remove::detach` unlocks before it removes, so `--yes` drops only that admin entry, never a blanket `prune`.

Check: `an_ended_agents_lock_holds_only_through_the_idle_window` — vanished with an ended agent or an old lock → drop-record; with a recent, undated or bare lock, or a recent lock of an agent that has not ended → keep. `gc_removes_only_finished_worktrees_and_never_opens_a_foreign_lock`: a vanished worktree under a September lock is dropped and its `.git/worktrees/<id>` is gone after `gc --yes`, one locked today stays.

Status: done 2026-10-20
Model: Claude Code / claude-opus-5-5
