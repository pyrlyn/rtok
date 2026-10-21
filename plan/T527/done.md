Do: an open claim whose agent has ended, or whose `last_seen` is older than gc's `--stale-lock` (default `7d`), no longer blocks `remove` or `adopt`. A holder seen inside that window still refuses, and the refusal names how long ago they were seen. A lock that names no agent, or an agent the store does not know, still holds, with the previous wording. `remove`, its dry run, and `adopt`/`claim` share that decision. The window is `gc::STALE_LOCK_DEFAULT`, the same string `--stale-lock` defaults to.

Check: store tests for an ended holder, a holder idle past `stale_lock`, and a holder seen today; `just check`.

Result: `Store::worktree_claim_holder` and `Holder::holds` cover the three store cases. `claim::held_against` is what `remove` (including preview) and `adopt`/`claim` consult; unit tests cover an ended holder, a holder idle past the window, a holder seen inside it (the refusal contains `holder seen`), and a lock the store cannot judge. `tests/worktree.rs` stayed green. This change touches `crates/`, so `just check` is the full gate and runs in CI.

Status: done 2026-10-19
