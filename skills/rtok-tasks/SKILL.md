---
name: rtok-tasks
description: Plan and track work as rtok tasks (task_create, task_list, task_next, rtok task), not TODO files, unless told otherwise.
---

# rtok-tasks

To plan, split or track work, keep the tasks in rtok, not in a TODO file or a hand-numbered
list, unless `AGENTS.md` or the user names another tracker. rtok hands out the ids, one
counter per project, so parallel agents never collide.

Tools (MCP; the CLI takes `--json` for the same shapes):

- `task_create` (`rtok task create "<title>" -d "<why, and what done means>"`): add a task;
  `parent` (`--parent R2`) makes a subtask `R2.1`. Quote the returned id, never invent one.
- `task_list` (`rtok task list`): the plan; `all` adds finished tasks.
- `task_get` (`rtok task show <id>`): one task with its description and subtasks.
- `task_status` (`rtok task status <id> in-progress|done|closed`): no status only reads.
  Set `in-progress` when you start, `done` when finished and checked.
- `task_next` (`rtok task next`): the first ready task.
- `task_ready` (`rtok task ready`): every claimable task, highest priority first.
- `task_claim` (`rtok task claim [id] --agent <id>`): claim it, or the first ready one
  without an id. A second claim by you changes nothing.
- `task_release` (`rtok task release <id>`): give it back. Only the holder, unless `--force`.
- `task_dep` (`rtok task dep <id> <blocker>`): `id` waits for `blocker`.
- `task_conflict` (`rtok task conflict <id> <other> [--remove]`): same files, not at once;
  `task_ready` flags it while the partner runs. Disk only.
- `task_parallel` (`rtok task parallel <id> [--off]`): safe beside any task. Disk only.
- `task_priority` (`rtok task priority <id> <0-4>`): 0 is highest, 2 the default.

CLI only:

- `rtok task init --adapter github --prefix R`: once per project; the adapter is disk,
  github or gitlab, the prefix 1-8 letters.
- `rtok task sync`: raise the id counters to the adapter's highest ids, report drift.
- `rtok task export github --dry-run`: mirror disk tasks one way to github or linear; the
  files stay the source. Drop `--dry-run` to send.

Never edit the task files by hand or reuse an id.

No `rtok`? `ketch install pyrlyn/rtok`.
