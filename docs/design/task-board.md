# Design: `rtok task` as a file board with two-way remote sync (T8)

Status: approved direction, implemented in steps T8.1 to T8.9. User docs land in
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
- A **library API**: the board, the sync engine and the trackers are a Kotlin library in
  `packages/`, and rtok's CLI, MCP tools and web UI are thin front ends over it. Programs in
  other languages reach the same API over **JSON-RPC** through the rtok daemon.
- **Parallel work without conflicts**, with an API for it: revisions with `if_rev`, field
  merges with `base_rev`, atomic operations and all-or-nothing batches, idempotency keys,
  claim leases with a TTL, and a change feed (`watch`). Concurrent clients share one machine;
  between machines the tracker sync is the meeting point.

This breaks the "interchangeable with the Rust binary" rule for `rtok task` only: the Rust
binary (0.17) cannot read the board layout. Nothing changes on disk until a project is new or
runs `rtok task migrate`.

## Model

**Board = files, always.** One Markdown file per task; the column is the folder.

**Remote = an optional partner tracker**, one per project: `github`, `gitlab`, `linear` or
`jira`. rtok syncs the board with it both ways. There is no "remote-only" store any more:
the old `[tasks] adapter = "github"` becomes `remote = "github"`, and the first sync pulls the
issues into files.

Ids keep rtok's shape, `<PREFIX><n>[.<n>[.<n>]]`. From T8.8 they come from the board's own
counter (`.state/ids.json`, under the board lock), seeded once from rtok's `task_counters`,
the board and the remote, and never reused. A remote issue never renames a file.

**Library = the engine**, rtok = the front ends. The package `kotlin-taskboard` (see Library)
owns the model, the file board, the concurrency rules, the sync engine and the trackers.
rtok owns config, finding the board root, the legacy layouts, `migrate`, and the CLI, MCP,
web and daemon front ends. Every front end calls the same `Board` API, so the rules below hold
whichever way a client comes in.

## Board layout

```
<main checkout>/tasks/          [tasks] dir, default "tasks"
  todo/R12-port-the-parser.md
  in-progress/R3-fix-proxy-cache.md
  done/R1-ci-for-macos.md
  closed/R2-drop-wasm.md
  .gitignore                    ".lock", ".state/" and ".sync/", written when rtok creates the board
  .lock                         flock for every write (was .claim.lock)
  .state/                       engine state, see Concurrency: ids, journal, index, leases, keys, txn
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
- `files` is the legacy flat layout: its old reader and writer, now under the engine (see
  below), plus one stderr warning per command that suggests `rtok task migrate`.
- `rtok task migrate [--dry-run] [--json]` turns a `files` directory into a board (each task
  to its column, done and closed split by status, front matter rewritten, `.claim.lock`
  renamed) and rewrites old config keys in the checkout's `.rtok.toml`. It is idempotent and
  prints every move.
- The `files` and `plan` layouts get the same API and concurrency rules: rtok adapts them to
  the library's `Store` interface and keeps their engine state under the main checkout's
  `.rtok/taskboard/`, so a committed `plan/` folder gains no state files.

## Library: `kotlin-taskboard`

A Kotlin Multiplatform package in the workspace's `packages/` (the `pyrlyn/packages` repo),
with the same native targets as rtok, a neutral name and no rtok imports. rtok includes it
with the `sharedBuild("kotlin-taskboard", "KOTLIN_TASKBOARD_DIR")` helper, like `kotlin-kit`.
Gradle group `pyrlyn.taskboard`:

| Module | Holds |
|---|---|
| `core` | `Task`, `Status`, `TaskId`, `Rev`, `Patch`, `Op`, errors, ready/next rules, field merge, the JSON codec (the one wire schema for CLI `--json`, MCP and JSON-RPC), the `Store` SPI and the engine that implements `Board` over any `Store` |
| `files` | `FileStore`: the column-folder layout above, front matter, atomic writes, `.lock` |
| `sync` | the sync engine and the `Remote` interface |
| `github`, `gitlab`, `linear`, `jira` | the trackers, over `kotlin-kit`'s `http` |
| `rpc` | JSON-RPC 2.0 method table, a transport-free dispatcher, a Kotlin client, the OpenRPC document |
| `testing` | an in-memory store, fake trackers, a concurrency test kit (N clients racing on one board) |

The public API, in short (every write returns the task with its new `rev`):

```kotlin
val board: Board = Boards.open(FileStore(dir), BoardOptions(clock, lease = 30.minutes, liveness))

board.snapshot(filter)                                    // BoardView: columns, boardSeq, warnings
board.get(id)                                             // Versioned<Task>: task + rev
board.create(NewTask(title, description), Write(key = "req-42"))
board.update(id, Patch(title = "x", addBlockedBy = setOf(b)), Write(baseRev = rev))
board.move(id, Status.Done, report = "…", Write(ifRev = rev))
board.claim(id = null, agent = "a1")                      // null id: claim the next ready task
board.release(id, agent = "a1")
board.batch(listOf(Op.Create(ref = "p", …), Op.Create(parent = Ref("p"), …)))  // all or nothing
board.changes(since = seq)                                // ChangePage, or Reset when too old
board.watch(since = seq)                                  // Flow<Change>
```

`Write(ifRev, baseRev, key, actor)` carries the concurrency options of every write. Errors
are one sealed type with stable codes, the same on every front end: `not_found`, `conflict`
(the current task and the clashing fields), `claimed` (holder and lease end), `invalid`,
`key_reused` (an idempotency key sent with a different request), `busy` (lock wait timed
out; retry), `remote` (a tracker call failed; a warning on local writes).

## Concurrency

Every client on the machine (CLI processes, the MCP server, the daemon, the web UI, other
programs through the library) serializes on the board's `.lock` (flock, waited for up to
`[tasks] lock_timeout`, default 10 s, then `busy`). Reads take no lock. Boards on network
file systems are not supported, because flock is not reliable there.

- **Revisions.** A task's `rev` is a short hash of its file bytes, so a hand edit changes it
  too. Every read returns `rev`; the board has a `seq` that grows with every change.
- **`if_rev`: strict compare-and-set.** A write with `if_rev` fails with `conflict` when the
  task's `rev` is different, and changes nothing.
- **`base_rev`: field merge.** A patch with `base_rev` applies when none of its fields
  changed since that revision, and fails with `conflict` naming each clashing field with its
  base, current and requested value otherwise. Without either, a patch applies field by
  field: it only carries the fields it sets, so two clients editing different fields never
  overwrite each other. Set fields (`blocked_by`, `conflicts_with`) take add/remove
  operations, which never clash. The engine finds "changed since" in the journal; a
  `base_rev` older than the journal keeps is treated as `if_rev`.
- **Atomic operations.** `create`, `update`, `move`, `claim`, `release` each run under one
  lock hold. `claim` with no id picks the next ready task and claims it in the same hold, so
  two agents never get the same task. A move writes the new file and removes the old one as
  one transaction (below).
- **Batches.** `batch(ops)` validates every operation, then applies all of them or none:
  the planned writes go to `.state/txn.json` (fsynced) before any file changes, and the next
  lock holder finishes a transaction a crash left behind. Ops refer to tasks created earlier
  in the batch by `ref`. Each op may carry its own `if_rev` / `base_rev`.
- **Idempotency keys.** Any write may carry `key`. The engine keeps key, request hash and
  result for 24 hours in `.state/keys.json`; a repeat returns the first result with
  `replayed: true`, and the same key with a different request fails with `key_reused`. Agents
  that retry after a timeout never create a duplicate.
- **Leases.** A claim is a lease: holder, start, end (`[tasks] lease`, default 30 minutes),
  kept in `.state/leases.json`; the task file holds only `assignee`. Claiming a task you
  already hold renews the lease (the heartbeat). After the end, another agent may claim it
  (`took_over_from` in the result) and `board` marks it `stale`; a host-supplied `liveness`
  check keeps a lease whose holder is still running (rtok answers it from its agent registry),
  so a long session does not lose its task. `release` and moves by someone other than the
  holder need `force`. The leases decide who holds a task; rtok's `task_claims` table only
  mirrors each agent's latest claim for the prompt hook, which names the current task without
  opening the board on every prompt.
- **Journal and watch.** Every change appends `{seq, at, actor, op, id, rev, fields:{name:
  [old, new]}}` to `.state/journal.jsonl` (the last 10 000 entries are kept).
  `changes(since)` returns the entries after `since`, or `reset` when `since` is older than
  the journal (the client re-reads the board). `watch` streams them. Hand edits are noticed by
  comparing files with `.state/index.json` (last known rev and fields per task); the next
  writer or watcher records them as `external` changes. Sync runs write through the engine
  with actor `sync:<remote>`, so watchers see pulled changes too.

## JSON-RPC

The `rpc` module maps the API one to one onto JSON-RPC 2.0 methods: `board.snapshot`,
`task.get`, `task.create`, `task.update`, `task.move`, `task.claim`, `task.release`,
`task.batch`, `board.changes` (long-poll with `wait_s`), `board.subscribe` /
`board.unsubscribe` (server notifications `board.changed`), and `rpc.discover` (the OpenRPC
document, checked into the package and tested against the code, so clients in Python or
TypeScript can be generated from it). Errors carry the codes above in `error.data.code`.
Every call names the project directory; rtok resolves the board from it as the MCP tools do.

rtok serves it two ways, one handler:

- the daemon listens on a Unix socket in rtok's runtime directory (directory 0700, socket
  0600: only the user can connect), newline-delimited JSON;
- `rtok task rpc --stdio` runs the same server over stdin/stdout for a client that starts its
  own process (scripts, CI, tests), no daemon needed.

The lock, not the daemon, keeps clients consistent, so the CLI and MCP keep working in
process while the daemon is down.

## Sync with a remote

### Config

```toml
[tasks]
prefix = ""            # id prefix; empty: first letter of the project name
dir = "tasks"          # board directory, relative to the root
layout = "auto"        # auto | board | files | plan
shared = true          # board under the main checkout, shared by every worktree
lease = "30m"          # claim lease; claiming again renews it
lock_timeout = "10s"   # how long a write waits for the board lock before `busy`
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

Every remote implements the library's `Remote` interface (list, get, create, update, move)
whose tasks carry `external` (number, url, node id) and the tracker's `updated` time. The
GitHub and GitLab modules start from rtok's `Github.kt` and `Gitlab.kt`, which move into the
package; Linear replaces `LinearExporter.kt` with a full adapter; Jira is new. `Export.kt` and
the exporters go away once `push` covers them. Field mapping:

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
machine (a lease, see Concurrency); with a remote it first fetches the issue and refuses when
an assignee from another machine holds it, then pushes the assignee. Across machines this is
best effort (the tracker has no compare-and-set), and the help says so. Leases stay local:
the tracker sees only the assignee.

## CLI

```
rtok task board [--all] [--json]                  the four columns; done and closed show the 10 newest unless --all
rtok task list [--status S,...] [--all] [--parent ID] [--json]
rtok task show <id> [--json]
rtok task create <title> [-d TEXT | --body-file PATH] [--parent ID] [--priority N] [--json]
rtok task edit <id> [--title T] [-d TEXT | --body-file PATH] [--priority N] [--parallel | --no-parallel] [--json]
rtok task move <id> <status> [--report TEXT|-] [--force] [--json]
rtok task claim [id] [--agent A] [--lease 30m] [--json]       claiming a task you hold renews its lease
rtok task release <id> [--agent A] [--force] [--json]
rtok task next [--json]
rtok task ready [--json]
rtok task dep <id> <blocker> [--remove] [--json]
rtok task conflict <id> <other> [--remove] [--json]
rtok task batch [FILE|-] [--json]                 JSON ops, all or nothing
rtok task watch [--since SEQ] [--once] [--json]   the change feed; JSON lines with --json
rtok task rpc --stdio                             the JSON-RPC server over stdin/stdout
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

Every write verb (`create`, `edit`, `move`, `claim`, `release`, `dep`, `conflict`) takes
`--if-rev REV`, `--base-rev REV` and `--key KEY` (see Concurrency), and every `--json`
output carries `rev` per task and the board `seq`. Errors keep their code: with `--json` the
CLI prints `{"error":{"code","message","data"}}`, and `conflict`, `claimed`, `busy` and
`not_found` get their own exit codes, listed in `docs/tasks.md`.

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

Eight tools instead of twelve. Every tool takes the optional `repo` (project dir) that main's
`AgentSession.projectDir` already resolves, and returns the same JSON as the CLI `--json`.
Every write tool also takes `if_rev?`, `base_rev?` and `key?` (see Concurrency), and the
session's agent id is the default `agent`.

| Tool | Params | Returns |
|---|---|---|
| `task_board` (read-only) | `status?`, `parent?`, `mine?`, `ready?`, `all?` | `{schema:1, kind:"board", board:{path,layout}, remote:{kind,target,sync,last_sync,pending}\|null, columns:[{status,tasks:[card]}], warnings:[]}` |
| `task_get` (read-only) | `id` | the task with `description`, `report`, `subtasks`, `remote` |
| `task_create` | `title`, `description?`, `parent?`, `priority?`, `blocked_by?` | the task |
| `task_update` | `id`, `title?`, `description?`, `status?`, `report?`, `force?`, `priority?`, `parallel?`, `add_blocked_by?`, `remove_blocked_by?`, `add_conflicts?`, `remove_conflicts?` | the task; all edits applied under one lock, status last |
| `task_claim` | `id?`, `agent?`, `lease?` | the claimed task with `changed`, `lease` and `took_over_from?`, or `null` when nothing is ready; claiming your own task renews the lease |
| `task_release` | `id`, `agent?`, `force?` | the task |
| `task_batch` | `ops` (create, update, move, claim, release, each with optional `ref`, `if_rev`, `base_rev`), `key?` | `{results:[...], seq}`; nothing is written when any op fails, and the error names the op |
| `task_changes` (read-only) | `since?`, `wait_s?` (long-poll, at most 30) | `{seq, changes:[{seq, at, actor, op, id, rev, fields}], reset}` |

A card is `{id, title, status, priority, parent, assignee, blocked_by, conflicts_with,
parallel, ready, stale, lease, rev, remote}`. The old tools (`task_list`, `task_status`, `task_next`,
`task_ready`, `task_dep`, `task_conflict`, `task_parallel`, `task_priority`) stay callable
for one release but are not listed in `tools/list`; `[mcp] tools` accepts both names.
Remote setup, sync, push, pull and migrate are not MCP tools: they are the user's call.
`task_board` shows the remote state, and auto mode keeps the board fresh for claims.

## Skills and agent instructions

- The skill source moves into this repo: `skills/rtok-tasks/` with `SKILL.md` (the board
  model, the eight tools with their CLI twins, the rules), `creating.md` (how to write a task:
  title, why, what done means, a `Check:` line) and `remote.md` (connecting a remote, sync,
  migrate; only when the user asks). Release packaging ships `skills/`.
- `rtok task prime` prints a short guide from the live config: board path and layout,
  remote, sync mode, whether the MCP tools are on. The skill points to it when unsure.
- Rules the skill states: claim before work (claim moves the card to in-progress); finish
  with `move <id> done --report` (or `task_update status=done report=...`); never write ids,
  counters or front matter by hand; never connect, sync, push or migrate on your own. For
  parallel work: pass the `rev` you read as `base_rev` when editing, re-read and retry on
  `conflict`, send a `key` with every create you might retry, claim again to keep a long task's
  lease, and use `task_changes` instead of re-reading the whole board.

## Fixes folded in

- Read-only tools no longer take the write lock or create files (`ready`, `next`, `board`).
- The board `.gitignore` covers `.lock`, `.state/` and `.sync/`.
- No-op writes keep `updated`.
- Case-insensitive unlink guard on moves.
- `dir` validation against `..` and absolute paths.
- Task warnings visible by default (stderr; `warnings` in MCP JSON).
- `--report` is stored by the board layout and written in the same atomic write as the move.

## Steps

| Task | Scope | Needs |
|---|---|---|
| T8.1 | Board layout, shared root, `migrate`, `board`, disk-layer fixes (board store kept free of rtok imports) | - |
| T8.8 | `packages/kotlin-taskboard`: `core`, `files`, `testing`; the engine with revisions, field merge, batches, idempotency keys, leases, journal and watch; rtok moves onto it | T8.1 |
| T8.2 | New CLI verbs and aliases, MCP eight tools and aliases, concurrency flags, `batch`, `watch`, `[tasks]` config and old keys, `doctor`, `prime`, web board on by default | T8.8 |
| T8.9 | JSON-RPC: the `rpc` module, OpenRPC document, `rtok task rpc --stdio`, the daemon's Unix socket | T8.2 |
| T8.3 | Sync engine and GitHub Issues + Projects v2 in the package, `.sync/` state, `remote`/`sync`/`push`/`pull`, auto mode | T8.2 |
| T8.4 | GitLab remote, two-way (title and description writes included) | T8.3 |
| T8.5 | Linear remote, full adapter replacing the exporter | T8.3 |
| T8.6 | Jira remote (Cloud and Data Center) | T8.3 |
| T8.7 | Skills in repo and packaged, `docs/tasks.md`, the package README, rtok README, PORTING, plan README decision | T8.2, T8.3, T8.9 |

T8.8 changes another repository: the package lands by a PR in `pyrlyn/packages`, and rtok's
CI, which pins that repository at a tag, needs a new tag and a bumped `ref`; the creator
approved both.
