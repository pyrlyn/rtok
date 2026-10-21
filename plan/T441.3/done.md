Third subtask of T441 (task adapters): one task id counter per project, shared by every process on the machine, with subtask counters, the project key, the prefix rule and a seeding hook.

Check: N processes × M allocations in parallel give unique, dense ids; two projects with the same prefix keep separate counters; seeding starts above the existing max; `just check` green.

Result: migration `0031_task_counters` (`project`, `parent`, `last`; `parent` is `''` for the top level or the parent's number path, so a prefix change keeps the numbering). `Store::allocate_task_id(project, prefix, parent)` reads, increments and writes in one `exclusive_transaction`; a bad prefix, a depth past 2 or a parent above the top counter is refused before a number is spent, and a lock held past `busy_timeout` names the allocator. `Store::seed_task_counter` only raises a counter (seeding from an existing tracker, skipping past another machine's id). `project::project_key` is the `origin` remote canonicalized to `host/owner/repo` across ssh, scp and https forms, else the main checkout's path; linked worktrees share their main checkout's key; `project_name` now shares the same `.git` walk. `tasks::resolve_prefix` takes `[tasks] prefix`, else the project name's first letter. `tests/task_allocator.rs` re-runs its own test binary as 8 processes × 25 allocations against one store file: 200 unique ids `R1`–`R200`. The card's crash case (write then rename) does not arise: SQLite's transaction is the atomic step.

Status: done 2026-10-07
Model: Claude Code / claude-opus-5-5
