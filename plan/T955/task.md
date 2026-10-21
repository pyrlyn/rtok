---
status: in progress
priority: P2
complexity: 4
readiness: 5%
agent: Claude Code / claude-sonnet-5-5
---

# T955. `rtok task` reads and writes the `plan/<id>/` layout

Creator request after T954. The `disk` adapter (`src/tasks/disk.rs`, T441 §7) keeps one file
per task, `<dir>/R12 - <slug>.md`, and moves done and closed tasks to `<dir>/done/`. The
project's own tasks now live as `plan/<id>/task.md` with front matter (`status`, `priority`,
`complexity`, `readiness`, `agent`) and a `plan/<id>/done.md` closing report that marks the
task closed (`tests/plan_dir.rs`). `rtok task` cannot read or write that layout, so agents edit
the folders by hand.

Done: the disk adapter reads and writes `plan/<id>/task.md` (title from the `# <id>. Title`
heading, the rest of the body as the description); `rtok task status <id> done` writes
`done.md` (the report from a flag or stdin) instead of moving a file; ids are allocated past
the highest folder name; `task_list` / `task_next` / `task_claim` see the same tasks the folder
listing shows; a project on the old `R12 - <slug>.md` layout keeps working or is migrated by a
command, never silently rewritten. `.rtok.toml` `[tasks]` names the directory.

Check: disk adapter tests create, claim and close a task in a temp `plan/` and the result
passes `tests/plan_dir.rs`'s rules; `rtok task list` in this repository lists exactly the
folders without `done.md`.

Execution plan:

1. `src/tasks/plan_dir.rs`: a second `TaskAdapter` for the folder layout. It edits only the `status`, `priority` and `agent` lines of `task.md` (`complexity`, `readiness` and the body stay byte-for-byte), derives the parent from the id, writes `done.md` when a task finishes (`Dropped:` first line for closed), and skips folders whose name is not a task id.
2. `[tasks.disk] layout = "auto" | "files" | "folders"` in `rtok-config`; `auto` picks folders when `dir` holds `<id>/task.md`, else the old `R12 - <slug>.md` files. `Project::open` selects the adapter. `.rtok.toml` in this repository names `dir = "plan"` and prefix `T`.
3. `rtok task status <id> done|closed --report <text|->` and the MCP `task_status` `report` argument write the closing report.
4. `MAX_DEPTH` 2 → 3 so active ids like `T385.9.3` parse.
5. Tests: create, claim and close in a temp `plan/` and run `tests/plan_dir.rs`'s rules over the result; old layout tests stay. Verify with `just check`.
