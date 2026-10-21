---
status: todo
priority: P2
complexity: 4
readiness: 0%
agent:
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
