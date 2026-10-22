---
status: in progress
priority: P1
complexity: 3
readiness: 80%
agent: Claude Code / claude-opus-5-5
---

# T954. Store tasks as `plan/<id>/` folders

Creator request. `plan.md` carried a table and 47 cards, `todo.md` repeated the ids and
`done.md` grew to 15.5k lines; concurrent sessions conflicted on all three. Each task now lives
in its own folder: `plan/<id>/task.md` is the task (front matter replaces the table row and
`todo.md`), and `plan/<id>/done.md` is the closing report and marks the task closed. Non-task
sections of `plan.md` move to `plan/README.md`; the one `done.md` record without an id goes
there too.

Plan: a one-off script splits the three files (re-run it on a newer `main` instead of resolving
conflicts by hand); `tests/plan_dir.rs` replaces `tests/plan_unique_ids.rs`; `AGENTS.md`,
`CONTRIBUTING.md`, `justfile`, CI comments and the per-plugin `Checks:` pointers name `plan/`.

Check: `cargo test --test plan_dir` passes on the migrated tree, and every non-blank line of the
old `plan.md` and `done.md` (headings and table rows aside) is present under `plan/`.
