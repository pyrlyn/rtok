---
status: in progress
priority: P2
complexity: 2
readiness: 0%
agent: MiMo / mimo
---

# T523. `worktree add` takes `T504 read-hook-same-path` as an id plus a slug

Found 2026-10-10: 27 `task id must match [a-z0-9]…` errors since 2026-10-06. Every one is an id and words in one argument (`T504 read-hook-same-path`, `M16.5 test toml`, `T330.5.3 junk-stale-worktrees`). `ident` in `src/worktree/add.rs` lower-cases, then rejects the space. Agents pass the id and the slug the way the branch is named, as one string. Nine further adds failed with `already exists: one worktree per task` on a retry of an add that had already landed.

Done when: one argument `T504 read-hook-same-path` plans task `t504` and branch `t504-read-hook-same-path`; spaces in the slug become dashes; a bare id is unchanged. `add` of a path whose branch is already that task returns the existing plan instead of failing. A string that is not an id after the split still fails the existing check.

Check: `plan` tests for the logged `T504 read-hook-same-path`, `M16.5 test toml` and `M16.3 push.rs through the config module`, and for an existing path of the same task; `just check`.
