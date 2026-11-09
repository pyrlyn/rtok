# Design: `rtok task` as a file board with two-way remote sync (T8)

Status: approved direction, implemented in steps T8.1 to T8.7. User docs land in
[docs/tasks.md](../tasks.md) with T8.7.

## What the creator asked for

- By default `rtok task` works on files, and the files are a task board: one folder per column
  (`todo/`, `in-progress/`, `done/`, `closed/`), so moving a card is moving its file.
- A clear API to connect the board to GitHub (Issues and Projects), GitLab, Linear or Jira,
  with **two-way** sync: an edit on either side reaches the other.
- A clear API for agents (MCP and CLI) and skills that teach it.
- A new API is allowed. The old CLI subcommands, MCP tools and config keys keep working for
  one release as hidden aliases that warn. Old `tasks/` files are read as before and converted
  by `rtok task migrate`.

This breaks the "interchangeable with the Rust binary" rule for `rtok task` only: the Rust
binary (0.17) cannot read the board layout. Nothing changes on disk until a project is new or
runs `rtok task migrate`.

## Model

**Board = files, always.** One Markdown file per task; the column is the folder.

**Remote = an optional partner tracker**, one per project: `github`, `gitlab`, `linear` or
`jira`. rtok syncs the board with it both ways. There is no "remote-only" store any more:
the old `[tasks] adapter = "github"` becomes `remote = "github"`, and the first sync pulls the
issues into files.

Ids stay rtok's: `<PREFIX><n>[.<n>[.<n>]]` from the machine counter (`task_counters`),
seeded from the board and the remote. A remote issue never renames a file.

## Board layout

```
<main checkout>/tasks/          [tasks] dir, default "tasks"
  todo/R12-port-the-parser.md
  in-progress/R3-fix-proxy-cache.md
  done/R1-ci-for-macos.md
  closed/R2-drop-wasm.md
  .gitignore                    ".lock" and ".sync/", written when rtok creates the board
  .lock                         flock for every write (was .claim.lock)
  .sync/github.json             last-synced base per task, see Sync state
```

- **Columns** are fixed: `todo`, `in-progress`, `done`, `closed` (closed means won't do).
  The column is the status; `Status` keeps its four values, renamed `Todo, InProgress, Done,
  Closed` with JSON names `todo`, `in-progress`, `done`, `closed`. Input also takes `open`,
  `pending`, `in_progress`, `in progress`, `doing`, `completed`, `wontdo`, `not-planned`,
  `dropped`. JSON output says `todo` where it said `open`.
- **Shared root.** With `[tasks] shared = true` (default) the board lives under the
  repository's main checkout (`ProjectRoot.main`), so every worktree, the CLI and the MCP
  server see one board and one lock. `shared = false` keeps today's per-checkout directory
  for projects that commit task files with their PRs. `dir` must be relative and must not
  leave the root (`..` is refused): a repository's `.rtok.toml` is untrusted.
- **File name** `<ID>-<slug>.md`, no spaces. The slug keeps Unicode letters and digits,
  lower-cased, other runs become `-`, at most 48 characters, `task` when empty. Readers also
  accept the old `<ID> - <slug>.md`. The id in the front matter is the truth; the file name
  is found by id prefix. Path comparisons before an unlink are case-insensitive, so a rename
  on APFS never deletes the file it just wrote.
- **Front matter**, in this order: `id`, `title`, `created`, `updated` (ISO 8601 UTC, e.g.
  `2026-11-09T10:54:00Z`; unix seconds are still read), then only when set: `parent`,
  `priority` (0 to 4, 2 is the default and not written), `assignee`, `blocked_by`,
  `conflicts_with`, `parallel`, `remote` (the linked issue URL). No `status` key: the folder
  is the status. A hand-written `status:` that disagrees with the folder is reported by
  `doctor`, and the folder wins. Unknown keys are kept on rewrite.
- **Body**: the description. A closing report goes under a final `## Report` heading, so the
  board layout stores `--report` (the old files layout could not).
- **Writes** are atomic (temp file, rename). A write that changes nothing does not touch the
  file or `updated`. A move writes the new file, then unlinks the old one.
- **Hand moves are allowed**: dragging a file to another column folder is a status change.
  Agents still go through rtok, so claims, counters and sync stay consistent.
- **Unknown folders** and unparseable files are skipped with a warning. The CLI prints task
  warnings to stderr by default; MCP returns them in a `warnings` array.

### Layouts and detection

`[tasks] layout = "auto" | "board" | "files" | "plan"` (moved from `[tasks.disk] layout`,
old key accepted).

- `auto`: `board` when a column folder exists or the directory is empty or missing; `plan`
  when `<dir>/<ID>/task.md` exists (the workspace `plan/` convention, unchanged); `files`
  when flat `<ID> - <slug>.md` task files exist. Decided once per command.
- `files` is the legacy flat layout: unchanged code path, plus one stderr warning per command
  that suggests `rtok task migrate`.
- `rtok task migrate [--dry-run] [--json]` turns a `files` directory into a board (each task
  to its column, done and closed split by status, front matter rewritten, `.claim.lock`
  renamed) and rewrites old config keys in the checkout's `.rtok.toml`. It is idempotent and
  prints every move.

## Sync with a remote

### Config

```toml
[tasks]
prefix = ""            # id prefix; empty: first letter of the project name
dir = "tasks"          # board directory, relative to the root
layout = "auto"        # auto | board | files | plan
shared = true          # board under the main checkout, shared by every worktree
remote = ""            # "" | github | gitlab | linear | jira
sync = "auto"          # auto: push each change, pull before claim/next/ready; manual: only `rtok task sync`
prefer = "newer"       # when both sides changed one field: newer | local | remote

[tasks.github]
repo = ""              # owner/name; empty: the origin remote
project = 0            # Projects v2 number; its Status column follows the card both ways
columns = {}           # Projects Status option per column, e.g. { in-progress = "Doing" }

[tasks.gitlab]
url = "https://gitlab.com"
project = ""           # group/name or id; empty: the origin remote

[tasks.linear]
team = ""              # team key, e.g. "ENG"; token in LINEAR_API_KEY

[tasks.jira]
site = ""              # https://<site>.atlassian.net or a Data Center base URL
project = ""           # project key, e.g. "ENG"; JIRA_API_TOKEN (+ JIRA_EMAIL on Cloud)
```

Old keys, accepted for one release with a warning: `[tasks] adapter` (`disk` means no remote,
`github`/`gitlab` set `remote`), `[tasks.disk] dir/layout`, `[tasks.export.github] repo`,
`[tasks.export.linear] team`. Tokens never come from config files: env vars, then `gh` /
`glab` as today (`Remote.kt`).

### Remote interface

Every remote is a full `TaskAdapter` (list, get, create, save, writeStatus) whose tasks carry
`external` (number, url, node id) and the tracker's `updated` time. GitHub and GitLab reuse
`Github.kt` and `Gitlab.kt`; Linear replaces `LinearExporter.kt` with a full adapter; Jira is
new. `Export.kt` and the exporters go away once `push` covers them. Field mapping:

| Field | GitHub | GitLab | Linear | Jira |
|---|---|---|---|---|
| id | labels `rtok`, `rtok:<ID>`; title `<ID>. <title>` | same | same | same labels; summary prefix |
| status | open or closed (completed / not planned) + `rtok:in-progress`; Projects v2 Status read and written | opened/closed + `status::*` labels | workflow state by type: unstarted, started, completed, canceled | transition to a status of category To Do / In Progress / Done; closed = Done + resolution "Won't Do" when the project has it |
| priority | label (`Meta.kt`) | label | native priority | native priority |
| assignee | `rtok:owner:` label (`Meta.kt`) | same | label | label |
| parent | sub-issue | `relates_to` link | native parent | parent field when the type allows it, else label |
| blocked_by, conflicts_with, parallel | labels (`Meta.kt`) | labels | labels | labels |
| report | closing comment | closing note | comment | comment |

A field a remote cannot store is kept locally and listed by `rtok task remote` as "not
synced", never dropped.

### Sync state

`<board>/.sync/<remote>.json`, written atomically under the board lock:

```json
{"version":1,"remote":{"kind":"github","target":"pyrlyn/rtok"},
 "tasks":{"R12":{"ref":{"number":41,"url":"https://github.com/pyrlyn/rtok/issues/41","node_id":"I_x"},
                 "base":{"title":"...","description":"...","status":"todo","priority":2,"assignee":null,
                         "parent":null,"blocked_by":[],"conflicts_with":[],"parallel":false},
                 "remote_updated":"2026-11-09T10:54:00Z"}}}
```

The base is what both sides agreed on at the last sync. It lives once per repository (in the
shared board), not per worktree. The `remote:` front matter key is written right after a
create, so a crashed push never creates a second issue.

### The sync run

Under the board lock and one lock per remote:

1. Read the board (L), the remote (R) and the base (B).
2. For every id in L, R and B, merge field by field: a field changed only on one side takes
   that side; a field changed on both sides to different values is a **conflict**, resolved
   by `prefer` (`newer` compares `updated` times) and listed in the report with both values.
   Only changed fields are written, on each side.
3. Local task without a remote issue: create the issue if the task is active (or with
   `--all`), write `remote:` and the base.
4. Remote issue with an id and no local file: create the file in its column; seed the counter.
5. Remote issue with the `rtok` label but no id label (a stray): give it the next id, write
   the id label and title prefix, create the file.
6. In the base but gone from the board: close the issue as not planned ("deleted locally").
7. In the base but gone from the remote: keep the file, drop `remote:` and the base, report.
8. Never reopen an issue closed on the remote unless the board moved it out of done/closed
   after the last sync. Labels rtok does not own are kept.

`--dry-run` prints the plan and writes nothing. `push` runs steps 2 to 6 for local changes
only; `pull` runs 2, 4 and 7 for remote changes only.

**Auto mode** (`sync = "auto"`): after a write command, rtok pushes that one task; before
`claim`, `next` and `ready` it pulls (the whole list at most once a minute; `claim <id>` fetches
that one issue). A failed push never fails the local write: it warns "not pushed, run
`rtok task sync`", and the next sync pushes it because the base did not move.

**Claims with a remote**: the claim is a compare-and-set under the board lock on this
machine; with a remote it first fetches the issue and refuses when another live agent holds
it, then pushes the assignee. Across machines this is best effort (the tracker has no
compare-and-set), and the help says so.

## CLI

```
rtok task board [--all] [--json]                  the four columns; done and closed show the 10 newest unless --all
rtok task list [--status S,...] [--all] [--parent ID] [--json]
rtok task show <id> [--json]
rtok task create <title> [-d TEXT | --body-file PATH] [--parent ID] [--priority N] [--json]
rtok task edit <id> [--title T] [-d TEXT | --body-file PATH] [--priority N] [--parallel | --no-parallel] [--json]
rtok task move <id> <status> [--report TEXT|-] [--force] [--json]
rtok task claim [id] [--agent A] [--json]
rtok task release <id> [--agent A] [--force] [--json]
rtok task next [--json]
rtok task ready [--json]
rtok task dep <id> <blocker> [--remove] [--json]
rtok task conflict <id> <other> [--remove] [--json]
rtok task remote [--json]                         board path and layout, remote and target, auth, sync mode, last sync, pending changes
rtok task remote set <github|gitlab|linear|jira> [--repo O/N] [--project N|KEY] [--url URL] [--team KEY] [--site URL] [--sync auto|manual] [--dry-run]
rtok task remote unset
rtok task sync [--dry-run] [--prefer newer|local|remote] [--all] [--json]
rtok task push [--dry-run] [--all] [--json]
rtok task pull [--dry-run] [--json]
rtok task migrate [--dry-run] [--json]
rtok task doctor [--fix] [--json]                 counters, broken files, status/folder disagreement, unknown folders, dangling deps, duplicate ids
rtok task prime                                   a short agent guide built from the live config
```

`remote set` checks the target and the token, writes `[tasks]` into the checkout's
`.rtok.toml` and prints what the first sync would do; it does not sync. `remote unset`
removes the remote and keeps the board and `.sync/` (a later `set` resumes).

Hidden aliases for one release; each prints `rtok task <old> is deprecated; use rtok task
<new>` to stderr and keeps its old output shape (status words are the new ones, `todo` for `open`,
everywhere):

| Old | New |
|---|---|
| `status <id>` | `show <id>` (status line) |
| `status <id> <s>` | `move <id> <s>` |
| `priority <id> <n>` | `edit <id> --priority <n>` |
| `parallel <id> [--off]` | `edit <id> --parallel / --no-parallel` |
| `init [--adapter A] [--prefix P]` | `remote set A` / `remote unset`, prefix written to `.rtok.toml` |
| `export <github\|linear> [--dry-run]` | one-way `push` to that tracker without setting it as the remote |
| `sync` (counter repair) | `doctor` repairs counters; `sync` without a remote does the repair and says no remote is set |

## MCP: the agent API

Six tools instead of twelve. Every tool takes the optional `repo` (project dir) that main's
`AgentSession.projectDir` already resolves, and returns the same JSON as the CLI `--json`.

| Tool | Params | Returns |
|---|---|---|
| `task_board` (read-only) | `status?`, `parent?`, `mine?`, `ready?`, `all?` | `{schema:1, kind:"board", board:{path,layout}, remote:{kind,target,sync,last_sync,pending}\|null, columns:[{status,tasks:[card]}], warnings:[]}` |
| `task_get` (read-only) | `id` | the task with `description`, `report`, `subtasks`, `remote` |
| `task_create` | `title`, `description?`, `parent?`, `priority?`, `blocked_by?` | the task |
| `task_update` | `id`, `title?`, `description?`, `status?`, `report?`, `force?`, `priority?`, `parallel?`, `add_blocked_by?`, `remove_blocked_by?`, `add_conflicts?`, `remove_conflicts?` | the task; all edits applied under one lock, status last |
| `task_claim` | `id?`, `agent?` | the claimed task with `changed`, or `null` when nothing is ready |
| `task_release` | `id`, `agent?`, `force?` | the task |

A card is `{id, title, status, priority, parent, assignee, blocked_by, conflicts_with,
parallel, ready, stale, remote}`. The old tools (`task_list`, `task_status`, `task_next`,
`task_ready`, `task_dep`, `task_conflict`, `task_parallel`, `task_priority`) stay callable
for one release but are not listed in `tools/list`; `[mcp] tools` accepts both names.
Remote setup, sync, push, pull and migrate are not MCP tools: they are the user's call.
`task_board` shows the remote state, and auto mode keeps the board fresh for claims.

## Skills and agent instructions

- The skill source moves into this repo: `skills/rtok-tasks/` with `SKILL.md` (the board
  model, the six tools with their CLI twins, the rules), `creating.md` (how to write a task:
  title, why, what done means, a `Check:` line) and `remote.md` (connecting a remote, sync,
  migrate; only when the user asks). Release packaging ships `skills/`.
- `rtok task prime` prints a short guide from the live config: board path and layout,
  remote, sync mode, whether the MCP tools are on. The skill points to it when unsure.
- Rules the skill states: claim before work (claim moves the card to in-progress); finish
  with `move <id> done --report` (or `task_update status=done report=...`); never write ids,
  counters or front matter by hand; never connect, sync, push or migrate on your own.

## Fixes folded in

- Read-only tools no longer take the write lock or create files (`ready`, `next`, `board`).
- The board `.gitignore` covers `.lock` and `.sync/`.
- No-op writes keep `updated`.
- Case-insensitive unlink guard on moves.
- `dir` validation against `..` and absolute paths.
- Task warnings visible by default (stderr; `warnings` in MCP JSON).
- `--report` is stored by the board layout and written in the same atomic write as the move.

## Steps

| Task | Scope | Needs |
|---|---|---|
| T8.1 | Board layout, shared root, `migrate`, `board`, disk-layer fixes | - |
| T8.2 | New CLI verbs and aliases, MCP six tools and aliases, `[tasks]` config and old keys, `doctor`, `prime`, web board on by default | T8.1 |
| T8.3 | Sync engine, `.sync/` state, GitHub Issues + Projects v2 two-way, `remote`/`sync`/`push`/`pull`, auto mode | T8.1, T8.2 |
| T8.4 | GitLab remote, two-way (title and description writes included) | T8.3 |
| T8.5 | Linear remote, full adapter replacing the exporter | T8.3 |
| T8.6 | Jira remote (Cloud and Data Center) | T8.3 |
| T8.7 | Skills in repo and packaged, `docs/tasks.md`, README, PORTING, plan README decision | T8.2, T8.3 |
