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

## Creating a task

A task is work that one agent can finish, check and hand in as one change. Write it so that another agent can take it without asking you anything.

1. **Look first.** Run `rtok task list --all`. If a task already covers the work, extend that task or add a subtask to it. Do not add a duplicate.
2. **Title.** One line that names the outcome, in the imperative: `Cache parsed configs`, not `Config stuff`.
3. **Description.** Say why the task exists and what done means: the behavior that changes, the files or area it touches, and the check that proves it, such as a test or a command. A long description can come from a file with `--body-file <path>`, or from stdin with `--body-file -`.
4. **Split.** Work that is more than one change, or that has parts that can run alone, becomes a parent task with subtasks: `rtok task create "<part>" --parent A12`. Subtasks cannot have subtasks of their own.
5. **Order.** Use `rtok task dep <id> <blocker>` when a task needs another task's result. Use `rtok task conflict <id> <other>` when two tasks touch the same files. Use `rtok task parallel <id>` when a task is safe to run beside any other task.
6. **Priority.** Use `rtok task priority <id> <0-4>` only when the task is more or less urgent than the default, `2`.
7. **Use the id rtok returns.** `create` prints the new id. Use it in branches, worktrees (`rtok worktree add A12`) and commit messages. Never choose an id yourself.

Example:

```bash
rtok task create "Cache parsed configs" \
  -d "Every hook call parses config.toml again. Done: a second call in one process reuses the parse; a test proves it."
# A12
rtok task create "Drop the cache when config.toml changes" --parent A12 \
  -d "Done: an edit to the file is seen on the next call; a test proves it."
# A12.1
rtok task dep A12 A9
```

From MCP, `task_create` takes `title`, `description` and `parent`. `task_dep` (`id`, `blocker`), `task_conflict` (`id`, `other`), `task_parallel` (`id`) and `task_priority` (`id`, `level`) do what the commands do.

## Creating tasks with a model

A coding agent, such as Claude Code, Codex or Cursor, can write the tasks for you. It calls the same MCP tools, so rtok still hands out the ids and the model never makes one up. rtok itself does not call a model to plan.

1. **Set up once.** Run `rtok agents install <host>`. It adds the `rtok mcp` server to the host and, on hosts that read skills, the `rtok-tasks` skill. The skill carries the steps of [Creating a task](#creating-a-task) for the model. If the project has no `[tasks]` section yet, run `rtok task init` first.
2. **Ask for tasks, not code.** Name the goal, the files or area, and what done means, and say that you want rtok tasks. For example:
   - "Plan the config cache as rtok tasks: one parent, a subtask per change, record the dependencies. Do not write code yet."
   - "Split A12 into subtasks, and mark the ones that touch the same files as conflicts."
   - "Turn the TODO comments in src/config/ into rtok tasks, each with a done check."
3. **Review first, if you want to.** Ask the model to show the titles and descriptions before it creates anything. It creates them after you agree.
4. **What the model does.** It calls `task_list` to look for existing work, then `task_create` for each task (with `parent` for a subtask), then `task_dep`, `task_conflict`, `task_parallel` and `task_priority`. It reports the ids that rtok returned. A host without MCP can do the same in its shell with `rtok task create --json`.
5. **Check the result.** Use `rtok task list`, `rtok task show <id>`, or the [task board](#task-board). There is no edit command: close a wrong task with `rtok task status <id> closed` and create a new one. Do not edit the task files by hand.

The model knows only what is in its context. Name the files and the done check yourself rather than asking it to guess.

## The disk adapter

The `disk` adapter is the default. Each task is one Markdown file in the `tasks` directory at the root of the checkout:

```text
tasks/A12 - ship-the-thing.md
tasks/done/A7 - old-work.md
```

- The file name is `<id> - <slug>.md`. The slug is lower-case letters and digits joined by dashes, at most 48 characters. The title is not in the name; it is in the front matter.
- The front matter holds `id`, `title`, `status`, `parent` (subtasks only), `created_at` and `updated_at` (Unix seconds). A claim adds `assignee`, `blocked_by` and `priority` (0–4, omitted when it is 2). The disk adapter also keeps `conflicts_with` and `parallel`, omitted when empty; GitHub and GitLab keep them as `rtok:conflicts:<id>` and `rtok:parallel` labels beside `rtok:needs:<id>` and `rtok:p:<n>`, and a user's own labels are left alone. Files written before those keys still parse. The description is the body, below the front matter.
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

### The folder layout

A project that keeps its plan as folders (`plan/<id>/task.md`, closed by `plan/<id>/done.md`) uses the same adapter: set `[tasks.disk] dir = "plan"`. `layout = "auto"` (the default) reads `dir` as folders when it already holds an `<id>/task.md`, and as the files above otherwise; `layout = "files"` or `"folders"` forces one, which is how an empty directory starts in folders. Nothing is converted for you.

- `task.md` has the front matter `status` (`todo` or `in progress`), `priority` (`P0`–`P3`, rtok's 0–3), `complexity`, `readiness` and `agent` (the assignee), then `# <id>. Title` and the body. A write changes only the `status`, `priority` and `agent` lines; the rest stays byte for byte.
- A task with a `done.md` is done; if its first line starts with `Dropped` it is closed. `rtok task status <id> done --report <text>` (or `--report -` for stdin) writes the report, and without one a one-line default. A finished task is not reopened by a command: remove its `done.md` yourself.
- Folder names that are not task ids are ignored. Blockers, conflicts, the parallel marker and priority 4 have no place in the front matter, so those commands refuse instead of dropping them.
- A task created through rtok gets `complexity: 3`, a zero `readiness` and, when its description has none, a `Check:` line to fill in.

## Commands

| Command | What it does |
| --- | --- |
| `rtok task init [--adapter disk\|github\|gitlab] [--prefix <letters>]` | writes `[tasks]` into the checkout's `.rtok.toml` and prints the next id; the prefix defaults to the project name's first letter |
| `rtok task create <title> [-d <text> \| --body-file <path\|->] [--parent <id>] [--json]` | adds a task under the next free id (`A12`, or `A12.3` under `--parent`); `--body-file -` reads the description from stdin |
| `rtok task list [--status <s,…>] [--all] [--parent <id>] [--json]` | active tasks, with subtasks indented under their parent; `--status` takes a comma-separated list |
| `rtok task show <id> [--json]` | one task with its subtasks, link and description |
| `rtok task status <id> [<status>] [--force] [--report <text>] [--json]` | reads the status, or sets it to `open`, `in-progress`, `done` or `closed`; `--report` is the closing report of a finished task (folder layout) |
| `rtok task next [--json]` | the first ready task. With no blockers, that is still the lowest open task with no active subtask |
| `rtok task ready [--json]` | every task that can be claimed, highest priority first (`0` before `2`) |
| `rtok task claim [id] [--agent <id>] [--json]` | claim `id`, or the first ready task. Pass `--agent` or set `RTOK_AGENT_ID`. On GitHub and GitLab the assignee write is last-write-wins, not compare-and-set |
| `rtok task release <id> [--agent <id>] [--force] [--json]` | clear the assignee and set the task open. Only the holder, unless `--force` |
| `rtok task dep <id> <blocker> [--json]` | `id` waits until `blocker` is done or closed. A cycle is refused and nothing is written |
| `rtok task priority <id> <0-4> [--json]` | set priority. `0` is highest, `2` is the default and is not stored |
| `rtok task conflict <id> <other> [--remove] [--json]` | the two tasks touch the same files, so they should not run at once. Symmetric: both files are written. `task ready` marks a task whose partner is in progress, and still lists it. Stored as `rtok:conflicts:<id>` labels on GitHub and GitLab |
| `rtok task parallel <id> [--off] [--json]` | mark a task safe to run beside any other task. Stored as the `rtok:parallel` label on GitHub and GitLab |

| `rtok task export github\|linear [--dry-run] [--json]` | mirror the disk tasks one way into GitHub Issues or Linear (see [Export](#export)) |

Without `--json` the commands print text. With `--json` they print the task as JSON.

## Export

`rtok task export <backend>` copies the disk tasks into an issue tracker so people who live there can see the plan. It works from the disk adapter only; the task files stay the source of truth, and rtok never reads an issue back into a task. Edits made on the tracker are overwritten on the next run.

- **GitHub** (`github`): one issue per task in `[tasks.export.github] repo` (else `[tasks.github] repo`, else the `origin` remote). The issue is written the way the `github` adapter writes it: title `<id>. <title>`, labels `rtok` and `rtok:<id>`, and the claim labels (`rtok:owner:…`, `rtok:needs:…`, `rtok:p:…`, `rtok:in-progress`). A subtask is linked as a sub-issue of its parent. Token: `GH_TOKEN`, `GITHUB_TOKEN`, else `gh auth token`.
- **Linear** (`linear`): one issue per task in the team `[tasks.export.linear] team` names by its short key (for example `ENG`); the export refuses to run with the key empty. Token: a personal API key in `LINEAR_API_KEY` (there is no CLI to fall back to). The issue carries the same title and labels as the GitHub one; rtok creates a label the team lacks. The status is Linear's workflow state, chosen by type: `open` the team's first `unstarted` state (else `backlog`), `in-progress` `started`, `done` `completed`, `closed` `canceled`; the state moves only when the status differs, so an issue parked in another `unstarted` or `started` column stays there. A subtask is created as a Linear sub-issue of its parent. Linear caps a personal key at 2,500 requests an hour; a limited request fails with the reason and the URL.
- A task with no issue gets one. A task whose title, description, status, priority, assignee, blockers or conflict and parallel markers differ from its issue updates the issue. `done` closes the issue as `completed`, `closed` as `not_planned`, and a task that is active again reopens it. Issues without an `rtok:<id>` label are never touched, and an issue whose task is gone is left as it is.
- A finished task with no issue is not exported (old history stays out of the tracker), unless an active subtask hangs under it.
- The run is idempotent: a second run against the same state sends no write. `--dry-run` reads the tracker, prints the planned creates, updates, closes and reopens, and sends nothing. `--json` prints the same plan as JSON.
- Writes are paced at one per second. A failure stops the run with the backend, the task and the request URL in the error; the writes before it stay, and the next run continues from there.

## Task board

`rtok web` has a read-only Tasks page (`g b`) and `rtok tui` a `tasks` tab. Both show the tasks of the project the process was started in, read through the same adapter and in the same shape as `rtok task list --all --json`. Nothing on the page writes a task.

- **Board**: one column per status (open, in progress, done, closed). A card shows the id, title, parent (`in R2`), assignee and the issue link of a `github` or `gitlab` task, plus markers: the priority when it is not the default `P2`, `needs <ids>` for blockers, `conflicts <ids>` and `parallel`. Cards in a column are ordered by priority, then id.
- **List**: a table with the details of the selected task beside it. Rows are tab stops; Enter or Space selects one. The board or list choice is remembered in the browser. The filter box matches id, title, parent and assignee.
- **No `[tasks]` section** in the user config or the project's `.rtok.toml`: the page says so and points at `rtok task init`. A project without tasks, or an adapter that cannot be reached (no token, no network), is a message on the page, not an error of the dashboard.
- **Size and freshness**: done and closed tasks are capped at the newest 50 each, with a note counting the rest; `rtok task list --all` has all of them. The `disk` adapter is read every 5 seconds, `github` and `gitlab` every 2 minutes so the page does not eat the API rate limit. The page links only to `http` and `https` issue URLs.

## MCP tools

`rtok mcp` serves the same operations as tools. Each returns the same JSON as the matching `--json` flag.

| MCP tool | Arguments | Same as |
| --- | --- | --- |
| `task_create` | `title` (required), `description`, `parent` | `task create` |
| `task_list` | `status` (a list, or a comma-separated string), `all`, `parent` | `task list` |
| `task_get` | `id` (required) | `task show` |
| `task_status` | `id` (required), `status`, `force`, `report` | `task status` |
| `task_next` | none | `task next` |
| `task_ready` | none | `task ready` |
| `task_claim` | `id`, `agent` | `task claim` |
| `task_release` | `id` (required), `agent`, `force` | `task release` |
| `task_dep` | `id` (required), `blocker` (required) | `task dep` |
| `task_priority` | `id` (required), `level` (required, 0–4) | `task priority` |
| `task_conflict` | `id` (required), `other` (required), `remove` | `task conflict` |
| `task_parallel` | `id` (required), `on` (default true) | `task parallel` |

## Config

Set `[tasks]` per project in `.rtok.toml`; `rtok task init` writes it there. The keys and defaults come from `crates/rtok-config/default.toml`:

```toml
[tasks]                               # task adapters; usually set per project in .rtok.toml
# adapter = "disk"                    # disk | github | gitlab
# prefix  = ""                        # task id prefix (R → R12); empty: first letter of the project name

[tasks.disk]
# dir = "tasks"                         # where the Markdown tasks live, relative to the project root
# layout = "auto"                       # files: "R12 - slug.md", done ones in <dir>/done; folders: "<id>/task.md", closed by "<id>/done.md" (plan/); auto: folders when <dir> holds one

[tasks.github]
# repo    = ""                        # owner/name; empty: the origin remote
# project = 0                         # Projects v2 number whose Status field tracks tasks; 0 = issues only

[tasks.gitlab]
# url     = "https://gitlab.com"      # base URL; set it for a self-hosted instance
# project = ""                        # group/name or numeric id; empty: the origin remote
```

The GitHub and GitLab adapters are not built yet (T441.7 and T441.8). Until they are, a task command with `adapter = "github"` or `"gitlab"` fails with an error that says so. Their sections will be added to this page when they land.
