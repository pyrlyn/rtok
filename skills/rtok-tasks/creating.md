# Creating rtok tasks

A task is work one agent can finish, check and hand in as one change. Write it so another
agent can take it without asking you anything.

1. Look first: `task_list` with `all` (`rtok task list --all`). Extend a task that already
   covers the work, or add a subtask to it; never add a duplicate.
2. Title: one imperative line that names the outcome (`Cache parsed configs`, not
   `Config stuff`).
3. Description: why the task exists and what done means. Name the behavior that changes, the
   files or area it touches, and the check that proves it (a test, a command). A long one
   goes through `rtok task create "<title>" --body-file -` from stdin.
4. Split: more than one change, or parts that can run alone, means a parent with subtasks
   (`parent`, `rtok task create "<part>" --parent A12`). Subtasks have no subtasks.
5. Order: `task_dep` (`rtok task dep <id> <blocker>`) when it needs another task's result;
   `task_conflict` (`rtok task conflict <id> <other>`) when two tasks touch the same files;
   `task_parallel` (`rtok task parallel <id>`) when it is safe beside anything.
6. Priority: `task_priority` (`rtok task priority <id> <0-4>`) only when it differs from the
   default 2.
7. Quote the id `task_create` returns in the branch, the worktree and the commits. Never
   choose an id yourself.

Asked to plan: write tasks, not code. Show the titles first when the user asks to see them,
then report the ids and the order rtok returned.

Before you start one: `task_claim`, then `task_status` `in-progress`; `done` once it is
checked.
