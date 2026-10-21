# T934. Done

A worktree claim write that meets another process's write lock now retries instead of warning
and losing the claim.

- `rtok_store::retry_locked(attempts, pause, op)` reruns an idempotent store write while it fails
  on "database is locked" (reuses `is_locked`).
- `worktree::claim::remember` and `claim::release` use it: 8 tries, each already bounded by the
  store's 1 s `busy_timeout`, 250 ms apart (about 10 s). When the lock outlasts that, `worktree
  add`, `adopt` and `remove` exit non-zero with `claim not stored; <path> stays locked, run
  \`rtok worktree adopt\`…` or `<path> was removed: claim not released: …`. The `WorktreeRemove`
  hook keeps a warning: a hook never fails the host.

## The long writer

Not measured live; named from the code and the store's own sizes (1.9 GB, 113 MB WAL, of which
the `symbols` table and its two root indexes are about 920 MB and `call_io` 756 MB). Writers that
hold `BEGIN IMMEDIATE`/`EXCLUSIVE` past one second are:

1. Graph indexing (`symbols.rs`): one write transaction per batch of files, ~140 inserts per file,
   on every session's MCP server and on each new worktree root.
2. `copy_symbol_rows`, which `worktree add` runs itself: the whole root copied in one
   transaction, so two parallel `worktree add` calls queue behind each other.
3. `purge_calls_older_than`, an `EXCLUSIVE` transaction at every MCP/proxy start, with a 30 s
   busy window.

Splitting the first two into short batches (as `clear_hook_bodies_in_batches` already does) is the
lasting fix; it is a separate task, not done here.

## Check

- `claim::lock_tests`: a held lock that ends inside the retries stores the claim; one that
  outlasts them returns the error and stores nothing; `release` waits out a lock.
- The tests take the lock after the store is open, which is how a claim write loses it. A CLI test
  was dropped: `Store::open` already waits out a lock that exists at startup, so it passed with
  the retry disabled. The old `remember` only warned, so the "outlasts" test could not pass on it.
- `just check`.
