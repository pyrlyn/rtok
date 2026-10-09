# Tasks

rtok keeps one numbered plan per project. Every checkout, worktree and agent of the project takes its task numbers from the same counter, so two agents do not pick the same id. The task commands and MCP tools are described here; the config keys are in [config.md](config.md), and the agent ids and worktrees that agents use are in [agents-and-worktrees.md](agents-and-worktrees.md).

## Ids

- A top-level id is a prefix of ASCII letters and a number, such as `A12`. A subtask adds one dotted number: `A12.3`.
- Depth is two. A task can have subtasks; a subtask cannot have subtasks of its own.
- The prefix is `[tasks] prefix`, from 1 to 8 letters. When it is empty, rtok uses the first letter of the project name, upper-cased (`rtok` gives `R`).
- Input is case-insensitive. Output is upper case.
- Ids come from a per-project counter in rtok's store. Before each new id, the counter is raised past the highest id the adapter already holds, so a task that arrived by a pull is never numbered again. Ids count under any prefix, so changing the prefix does not reset the numbering. Subtask numbers are counted per parent.

## Statuses

| Status | Meaning |
| --- | --- |
| `open` | in the plan, not started |
| `in-progress` | in the plan, being worked on |
| `done` | finished; leaves the plan |
| `closed` | won't do; leaves the plan |

- `open` and `in-progress` are active. `list` shows active tasks only, unless `--all` adds the finished ones.
- A parent with active subtasks cannot be finished. Setting it to `done` or `closed` is refused and the error lists the subtasks, unless `--force` is given.
- The parser also takes the words agents reach for: `todo` and `pending` for `open`, `completed` for `done`, `wontdo`, `won't-do` and `not-planned` for `closed`. Underscores work in place of hyphens, so `in_progress` is accepted.

## The disk adapter

The `disk` adapter is the default. Each task is one Markdown file in the `tasks` directory at the root of the checkout:

```text
tasks/A12 - ship-the-thing.md
tasks/done/A7 - old-work.md
```

- The file name is `<id> - <slug>.md`. The slug is lower-case letters and digits joined by dashes, at most 48 characters. The title is not in the name; it is in the front matter.
- The front matter holds `id`, `title`, `status`, `parent` (subtasks only), `created_at` and `updated_at` (Unix seconds). A claim adds `assignee`, `blocked_by` and `priority` (0–4, omitted when it is 2). Files written before those keys still parse. The description is the body, below the front matter.
- The files belong to the checkout a command runs in. The numbers are shared through the store, but a worktree reads and writes the task files on its own branch, so they reach other checkouts through git.
- Done and closed tasks move to `tasks/done/`, so the plan is the directory listing.
- Writes go to a temporary file that is renamed into place.
- Files that do not match `<id> - <slug>.md` are ignored, so a README beside the tasks is safe.

Example of a task file:

```markdown
---
id: A12
title: Ship the thing
status: open
created_at: 1790000000
updated_at: 1790000000
---

Why the task exists and what done means.
```

## Commands

| Command | What it does |
| --- | --- |
| `rtok task init [--adapter disk\|github\|gitlab] [--prefix <letters>]` | writes `[tasks]` into the checkout's `.rtok.toml` and prints the next id; the prefix defaults to the project name's first letter |
| `rtok task create <title> [-d <text> \| --body-file <path\|->] [--parent <id>] [--json]` | adds a task under the next free id (`A12`, or `A12.3` under `--parent`); `--body-file -` reads the description from stdin |
| `rtok task list [--status <s,…>] [--all] [--parent <id>] [--json]` | active tasks, with subtasks indented under their parent; `--status` takes a comma-separated list |
| `rtok task show <id> [--json]` | one task with its subtasks, link and description |
| `rtok task status <id> [<status>] [--force] [--json]` | reads the status, or sets it to `open`, `in-progress`, `done` or `closed` |
| `rtok task next [--json]` | the first ready task. With no blockers, that is still the lowest open task with no active subtask |
| `rtok task ready [--json]` | every task that can be claimed, highest priority first (`0` before `2`) |
| `rtok task claim [id] [--agent <id>] [--json]` | claim `id`, or the first ready task. Pass `--agent` or set `RTOK_AGENT_ID`. On GitHub and GitLab the assignee write is last-write-wins, not compare-and-set |
| `rtok task release <id> [--agent <id>] [--force] [--json]` | clear the assignee and set the task open. Only the holder, unless `--force` |
| `rtok task dep <id> <blocker> [--json]` | `id` waits until `blocker` is done or closed. A cycle is refused and nothing is written |
| `rtok task priority <id> <0-4> [--json]` | set priority. `0` is highest, `2` is the default and is not stored |

Without `--json` the commands print text. With `--json` they print the task as JSON.

## MCP tools

`rtok mcp` serves the same operations as tools. Each returns the same JSON as the matching `--json` flag.

| MCP tool | Arguments | Same as |
| --- | --- | --- |
| `task_create` | `title` (required), `description`, `parent` | `task create` |
| `task_list` | `status` (a list, or a comma-separated string), `all`, `parent` | `task list` |
| `task_get` | `id` (required) | `task show` |
| `task_status` | `id` (required), `status`, `force` | `task status` |
| `task_next` | none | `task next` |
| `task_ready` | none | `task ready` |
| `task_claim` | `id`, `agent` | `task claim` |
| `task_release` | `id` (required), `agent`, `force` | `task release` |
| `task_dep` | `id` (required), `blocker` (required) | `task dep` |
| `task_priority` | `id` (required), `level` (required, 0–4) | `task priority` |

## Config

Set `[tasks]` per project in `.rtok.toml`; `rtok task init` writes it there. The keys and defaults come from `config/default.toml`:

```toml
[tasks]                               # task adapters; usually set per project in .rtok.toml
# adapter = "disk"                    # disk | github | gitlab
# prefix  = ""                        # task id prefix (R → R12); empty: first letter of the project name

[tasks.disk]
# dir = "tasks"                       # one Markdown file per task, relative to the project root; done ones go to <dir>/done

[tasks.github]
# repo    = ""                        # owner/name; empty: the origin remote
# project = 0                         # Projects v2 number whose Status field tracks tasks; 0 = issues only

[tasks.gitlab]
# url     = "https://gitlab.com"      # base URL; set it for a self-hosted instance
# project = ""                        # group/name or numeric id; empty: the origin remote
```

The GitHub and GitLab adapters are not built yet (T441.7 and T441.8). Until they are, a task command with `adapter = "github"` or `"gitlab"` fails with an error that says so. Their sections will be added to this page when they land.
