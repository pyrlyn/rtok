---
status: todo
priority: P2
complexity: 2
readiness: 0%
agent:
---

# T957. `worktree gc` never drops the record of a vanished worktree under a foreign lock

Bug. In `gc::decide` the `held` branch runs before `match entry.state`, so a `State::Stale` record (its directory is gone) under someone else's git lock gets `keep("locked by …")` on every run and never reaches `Verdict::DropRecord`. The comment says a vanished worktree "may still be the only copy of someone's work", but a missing directory holds no work. Seen: `_worktrees/completions-shared/apps/rtok-completions-shared` and `_worktrees/rtok-audit-20261018` (locked by OpenAI / GPT-6, 0 B, directories missing) are listed as `stale` and kept forever.

Done: a stale record under a foreign lock is dropped. Either it is dropped at once (`git worktree unlock` and then removing only that admin entry, never a blanket `prune`), or after `stale_lock`, whichever the creator picks. The choice is written in the card before the code.

Check: an rstest case in `gc.rs` for Stale + foreign lock → `drop-record`; an integration test where the worktree directory is deleted under a lock and `gc --yes` removes its `.git/worktrees/<id>`; `just check`.
