---
name: rtok-tasks
description: Load before claiming, updating or closing a task in a project that keeps plan.md, todo.md and done.md.
---

# rtok-tasks

Such a project tracks every active task in `plan.md`. Until rtok ships task commands, edit the
three files by hand, in the task's own worktree, and keep them in sync.

## Claim

- Read the `plan.md` table first. A row `in progress` under another agent is not yours.
- Scope or done criteria unclear: ask the user, do not guess. Blockers go first.
- Set the row to `in progress`, Agent `<provider> / <model>`; write a short plan (steps, files,
  how to verify) into the task's card, never into the table. Sync `todo.md` at once.
- A task enters `plan.md` only from `roadmap.md`, with the user's approval. Its id is the
  highest `T<n>` on `origin/main` and in open PRs, plus one; other sessions pick ids too, so
  check again before you push.

## Format

- Table columns: `#`, Status (`todo` | `in progress`), Priority `P0`–`P3`, Complexity `1`–`5`,
  Readiness `0%`–`100%`, Agent (empty while `todo`). No titles in the table.
- Cards under the table: `### T1. Title`, then why the task exists and what done means.
- `todo.md`: one `- T1. Title` line per active task, the same set as the table.

## Close

Remove the row, the card and the `todo.md` line; put the whole task (id, title, description)
into `done.md` with a blank line above its heading. A `done.md` merge conflict keeps both sides.

## Never

- Dates or deadlines in these files.
- Moving anything out of `ideas.md` without the user's approval.
- Touching a task another agent holds.
