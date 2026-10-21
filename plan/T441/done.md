Creator request 2026-10-07 (voice). Plan only: no code until the creator approves the design that comes out of T441.1. Approved 2026-10-07 after T441.1: own core (§12); the remaining §12 questions are settled in the subtask they affect. Every subtask below builds on the T441.1 research findings.

Check: T441.1's findings are recorded in `research.md` and the creator approves the design; each later subtask closes only when its §10 tests and `just check` pass.

#### Goal

Agents in any project create, read and close tasks only through rtok (AirTalk). rtok intercepts task creation, hands out a unique task number from one allocator, and stores the task through the project's configured **adapter**: plain files on disk, GitHub Issues + Projects, or GitLab Issues. Many agents work in parallel, so numbering must never collide. When a task is done, the adapter archives it (disk) or moves it to a done state (GitHub/GitLab) so it no longer shows up in the plan.

#### Terms

- **Provider** — already taken in rtok: an upstream LLM API the proxy talks to (`src/proxy/anthropic.rs`, `openai_chat.rs`, `gemini.rs`). Not reused here.
- **Adapter** — new: a task storage backend (`disk`, `github`, `gitlab`). One adapter per project, chosen in config. Lives in a new `tasks` module (or crate `crates/rtok-tasks`), never under `proxy`.
- **Task** — `id`, title, description, status, optional parent. **Plan** — the list of tasks that are not done.
- **Allocator** — the single per-machine component that hands out task ids.

#### 1. Research (T441.1, first; everything else follows it)

Findings so far (verified 2026-10-07; T441.1 finishes them with a short `research.md` section):

| Prior art | Storage | IDs under parallel agents | Surface | Statuses | GitHub/GitLab sync | What we take |
| --- | --- | --- | --- | --- | --- | --- |
| [Backlog.md](https://github.com/MrLesk/Backlog.md) | One Markdown file per task in `backlog/`, config `backlog.config.yml` | Sequential with a configurable prefix (`TASK-1`), subtasks `TASK-5.3`; allocation under `withCreateLock()`, counting other worktrees' uncommitted files (per repo) | CLI first (`backlog task create/list/edit`, `--plain`/`--json`), optional stdio MCP (`backlog mcp start`) over the same core ([MCP README](https://github.com/MrLesk/Backlog.md/blob/main/src/mcp/README.md)) | Configurable, default To Do / In Progress / Done; archive folder | None built in | Markdown-file disk layout, configurable prefix, one core with CLI + MCP as thin wrappers, `claude mcp add` / `codex mcp add` one-liners, agent instructions pointing at a workflow doc |
| [Taskmaster AI](https://github.com/eyaltoledano/claude-task-master) | `.taskmaster/tasks/tasks.json` | Numeric per tag, subtasks `1.2`, `1.2.1` ([task structure](https://github.com/eyaltoledano/claude-task-master/blob/main/docs/task-structure.md)); read-modify-write of one JSON file under its own `.lock` file (`withFileLock`) | CLI `task-master list/show/set-status` and MCP `get_tasks`, `get_task`, `next_task`, `set_task_status`; tool tiers via `TASK_MASTER_TOOLS` to keep tool lists small | pending, in-progress, done, review, deferred, cancelled | None built in | Dotted subtask ids, `next` command, small default MCP tool set; avoid one JSON file per repo as the only state |
| [beads](https://github.com/steveyegge/beads) | Dolt (versioned SQL) per repo | Hash ids (`bd-a1b2`) from content + time + creator + nonce, retried on collision, length grows with the DB ([FAQ](https://github.com/steveyegge/beads/blob/main/docs/FAQ.md)); hierarchical `bd-a3f8.1` | CLI `bd` with JSON output | open, in progress, blocked, closed | `bd github`, `bd gitlab`, `bd linear`, `bd jira`, … with pull/push/sync | The reason sequential ids fail across branches/machines; we keep human `R12` ids from one allocator but adopt its import + external-id mapping and "same id = update" rule |
| [GitHub MCP server](https://github.com/github/github-mcp-server) | GitHub | GitHub's own per-repo issue number | MCP toolsets `issues` (`issue_read`, `issue_write`, `list_issues`, `sub_issue_write`) and `projects` (`projects_get/list/write`, needs a PAT with `project` scope) | open/closed + Project Status field | Native | The GitHub adapter can call the same REST/GraphQL endpoints; sub-issues map to `R2.1`; Projects need the `project` scope |
| [Linear MCP](https://linear.app/docs/mcp) | Linear (hosted) | Linear's team key + number (`ENG-123`) | Remote MCP over Streamable HTTP at `mcp.linear.app/mcp`, `save_issue` create-or-update | Team workflow states | Native | Upsert tool shape (`save` with/without id); hosted ids keyed by a short team prefix, like our project prefix |
| [Claude Code tasks](https://code.claude.com/docs/en/agent-sdk/todo-tracking) (TodoWrite; TaskCreate/TaskGet/TaskUpdate/TaskList) | Session or shared list (`CLAUDE_CODE_TASK_LIST_ID`, [env vars](https://code.claude.com/docs/en/env-vars)) | Host-internal | Built-in tools only | pending, in_progress, completed | None | Agents already think in these three statuses; our status names map 1:1 |
| [Codex CLI `update_plan`](https://github.com/openai/codex/blob/main/codex-rs/protocol/src/plan_tool.rs) | In-session only | None (whole plan resent each call) | Built-in tool | pending, in_progress, completed; one step in progress | None | Same status vocabulary; our tasks are durable and shared, theirs are a per-turn checklist — keep both, do not replace |
| [AGENTS.md](https://agents.md/) | Markdown convention | — | — | — | — | Install writes a short `AGENTS.md`/`CLAUDE.md` rule: "create and update tasks only through `rtok task`" |

Re-checked in T441.1 (`research.md` §35.1): Backlog.md and Taskmaster lock id allocation per repository and beads uses hash ids, and beads already syncs GitHub and GitLab. What none has is one counter per project shared by every checkout, worktree and agent host on the machine, served through the MCP server and CLI the agents already have — that is what rtok adds, and §12 asks whether it is worth building rather than adopting beads.

T441.1 done means: the table above re-checked, plus answers to the open questions it can settle (GitLab work items API vs issues, GitHub sub-issues limits, rate limits), recorded in `research.md`; the design below adjusted to the findings.

#### 2. Recommended architecture: one core, thin CLI and MCP front-ends

- One core (`rtok_tasks`): domain types, allocator, adapter trait, the three adapters. No I/O policy in the front-ends.
- **CLI** `rtok task …`: for agents that prefer shell (Codex CLI, Claude Code with Bash) and for humans and scripts. `--json` on every command.
- **MCP**: new tools on the existing `rtok mcp` stdio server (no second server, no second install): `task_create`, `task_list`, `task_get`, `task_status`, `task_next`. Small tool set, like Taskmaster's core tier. Same JSON shapes as the CLI.
- **Transport**: stdio for every host (Claude Code, Claude Desktop, Codex CLI, Codex app/IDE all speak it). No HTTP listener.
- **Install/config** (through the existing `rtok agents install <host>`; entries already managed, host configs touched only at our entry):
  - Claude Code: `claude mcp add rtok --scope user -- rtok mcp` (or the plugin).
  - Claude Desktop: `claude_desktop_config.json` → `"mcpServers": {"rtok": {"command": "rtok", "args": ["mcp"]}}` (absolute path to `rtok`, since the app has no shell PATH).
  - Codex CLI and Codex app/IDE share `~/.codex/config.toml`: `[mcp_servers.rtok]` `command = "rtok"`, `args = ["mcp"]` (or `codex mcp add rtok -- rtok mcp`).
  - Every host: the AGENTS.md/CLAUDE.md rule line from §1.
- **Many processes at once**: every Claude/Codex window spawns its own `rtok mcp` and CLI calls are their own processes, so there is no in-memory singleton. The singleton is the allocator state file under rtok's data dir guarded by an OS file lock (§5); all processes go through it. Optional later: route allocation through the existing hook resident (`src/hooks/resident.rs`) to save a lock round trip.

#### 3. CLI commands (proposed names)

- `rtok task create "<title>" [-d <text>|--body-file <path>] [--parent R2] [--status open]` → prints the id (`R12`, `R2.3`).
- `rtok task list [--status open,in-progress] [--all] [--json]` → the plan (done tasks hidden unless `--all`).
- `rtok task show <id> [--json]` → title, description, status, parent, subtasks, external link.
- `rtok task status <id>` → prints status; `rtok task status <id> <open|in-progress|done>` → sets it (`done` triggers §8).
- `rtok task next` → first open task without an open parent dependency.
- `rtok task init [--adapter disk|github|gitlab] [--prefix R]` → writes project config.
- `rtok task sync` → reconcile local cache/index with the adapter (GitHub/GitLab).

#### 4. Data model

- `TaskId { prefix: String, path: Vec<u32> }` — `R12` = prefix `R`, path `[12]`; `R2.1` = `[2, 1]`. Parse/print round-trip; case-insensitive input, canonical upper-case output.
- `Task { id, title, description, status, parent: Option<TaskId>, created_at, updated_at, external: Option<ExternalRef> }`.
- `Status { Open, InProgress, Done, Closed }`: `Closed` is "won't do"; both it and `Done` leave the plan (T441.2, `src/tasks/mod.rs`). Maps to Claude Code / Codex `pending/in_progress/completed`.
- `ExternalRef { adapter, number_or_iid, url, node_id }` — the GitHub/GitLab issue the task lives in.
- Allocator state: `{ project_key → { prefix, next_top: u32, next_sub: map<top, u32> } }`.

#### 5. ID allocation

- One allocator per machine: a Diesel table in rtok's existing store (T441.1, `research.md` §35.4). Allocation is one `exclusive_transaction` (read → increment → write); the store already runs WAL with `busy_timeout`, so it is atomic across processes and crash-safe on macOS, Linux and Windows without a second file or lock.
- The project key is the `origin` remote as `host/owner/repo` (two clones share it), else the main checkout's path, never the prefix, so two projects with the same first letter keep separate counters (T441.3, `project::project_key`).
- Prefix: first letter of the project name, upper-cased (rtok → `R`), overridable in config. Two projects with the same prefix are allowed (counters are per project) but `rtok task init` warns; a two-letter override is recommended for cross-project references.
- Subtasks: `R2.1`, `R2.2` from the parent's own sub-counter; depth limit 2 at first.
- Seeding: on `init` for an existing tracker the counter starts above the highest id already present (scan disk files / GitHub titles / labels).
- GitHub/GitLab: the rtok id is ours, the issue number is theirs. Issue title is prefixed `R12. <title>` and carries label `rtok:R12`; `ExternalRef` stores the mapping. Never derive our number from theirs (PRs share GitHub's counter).
- Different machines: one allocator per machine cannot stop two machines issuing the same `R13`. Adapter `create` checks for an existing `rtok:R13` and on conflict re-allocates past the highest remote id (GitHub/GitLab label search is the cross-machine source of truth). Open question whether that is enough.

#### 6. Adapter trait

```rust
trait TaskAdapter {
    fn create(&self, task: &NewTask, id: &TaskId) -> Result<Task>;
    fn list(&self, filter: &Filter) -> Result<Vec<Task>>;
    fn get(&self, id: &TaskId) -> Result<Option<Task>>;
    fn set_status(&self, id: &TaskId, status: Status) -> Result<Task>;
    fn archive(&self, id: &TaskId) -> Result<()>; // done flow, §8
    fn max_id(&self, prefix: &str) -> Result<Option<TaskId>>; // seeding, collisions
}
```

As built in T441.4 (`src/tasks/adapter.rs`): `archive` is folded into `write_status` (done/closed archive), and the free `set_status` guards parents for every adapter.

Sync and blocking HTTP is fine for a CLI; errors carry the adapter name and the external URL. No adapter shells out to `gh`/`glab` unless T441.1 decides to (token handling).

#### 7. Adapters

- **disk** (default, offline): one Markdown file per task under `tasks/` in the project, `R12 - <slug>.md` with a small front matter (id, status, parent) and the description as body, the Backlog.md layout. Writes are temp + rename. Optionally renders `plan.md`/`todo.md` rows in this workspace's format (open question).
- **github**: issues via REST; status via a Projects v2 Status field (`Todo` / `In Progress` / `Done`) via GraphQL; subtasks as sub-issues (100 per parent; REST takes the sub-issue's database id). Token from `GH_TOKEN`, then `GITHUB_TOKEN`, then `gh auth token`; Projects need the `project` scope, and user-owned projects a classic token. Writes are paced (500 content-creating requests/hour, one per second). Config names the owner/repo and project number; field and option ids are looked up once and cached.
- **gitlab**: issues via REST v4 (`/projects/:id/issues`), done = closed. Subtasks are tasks under the issue, linked through the GraphQL work item hierarchy (REST has no parent field). Status: native Status through GraphQL on Premium/Ultimate 18.4+, else a `status::in-progress` label the adapter swaps itself (scoped labels are Premium too). Token `GITLAB_TOKEN`, scope `api`; self-hosted base URL in config.

#### 8. Done / archival flow

`rtok task status R12 done` (or MCP `task_status`):
- disk: file moves to `tasks/done/` (or is appended to `done.md` and removed), so `list` never returns it again.
- github: Project Status → `Done` and the issue is closed (`state_reason: completed`); `list` filters done out.
- gitlab: issue closed, status label set to done.
- Parent with open subtasks cannot be done (error lists them) unless `--force`. The id is never reused.

#### 9. Config

Project `.rtok.toml` (rtok's own TOML, schema from types, one config module — T238; the `[tasks]` section landed in T441.2):

```toml
[tasks]
adapter = "github"        # disk | github | gitlab
prefix = "R"              # default: first letter of the project name
[tasks.disk]
dir = "tasks"
[tasks.github]
repo = "pyrlyn/rtok"
project = 7               # Projects v2 number; optional
[tasks.gitlab]
url = "https://gitlab.com"
project = "group/name"
```

#### 10. Tests to write

- `TaskId` parse/print round-trip, ordering, invalid input.
- Allocator: N processes × M allocations in parallel (spawned test binaries) → all ids unique and dense; crash between write and rename leaves a valid state; lock timeout reports clearly.
- Two projects with the same prefix keep separate counters; seeding starts above the existing max.
- disk adapter: create/list/get/status/archive on a temp dir; done tasks never listed.
- github/gitlab adapters against recorded HTTP fixtures (no live network in CI, no real agents in tests): create maps title/label, status moves the Project field, done closes; collision re-allocation.
- CLI ↔ MCP parity: same inputs give the same JSON.
- Install: host config entries for Claude Code, Claude Desktop, Codex stay byte-for-byte except our entry.

#### 11. Milestones

1. **T441.1 Research** — finish §1, record in `research.md`, settle what it can of §12, adjust this card.
2. **T441.2 Core types** — `TaskId`, `Task`, `Status`, config section; unit tests.
3. **T441.3 Allocator** — locked file (or Diesel table), project key, prefix rule, subtask counters, seeding hook; the parallel-process test.
4. **T441.4 Adapter trait + disk adapter** — trait, disk layout, archive on done.
5. **T441.5 CLI** — `rtok task create/list/show/status/next/init`, `--json`.
6. **T441.6 MCP tools** — `task_*` on `rtok mcp`, parity test. The server entry is already what `rtok agents install` writes for every host, so the tools need no install of their own.
7. **T441.7 GitHub adapter** — issues, sub-issues, label mapping, collision check. Done; Projects v2 Status split into T441.11, `sync` into T441.12.
8. **T441.8 GitLab adapter** — done: issues, `status::` labels, close on done, self-hosted https URL, `relates_to` parent link (#819).
9. **T441.9 Docs** — README/docs section in English with `docs/ru|uk` synced (CONTRIBUTING.md), `toolchain.md` for any new dependency.
10. **T441.10 Instruction line** (split from T441.6) — the AGENTS.md/CLAUDE.md rule line from §1 installed through `rtok agents install`, host config entries byte-for-byte except ours (§10).
11. **T441.11 GitHub Projects v2 Status** (split from T441.7) — done: new issues join the `[tasks.github] project` board and its Status follows the task (#817).
12. **T441.12 `rtok task sync`** (split from T441.7) — done: counters raised to what the adapter holds, drift reported, the adapter never written (#820).
13. **T441.13 Task-board extras** (from T405, creator 2026-10-10; `research.md` §28.4) — a `task` field on agent messages (F8); conflict and parallel markers between tasks (F9); an optional GitHub Issues or Linear exporter (F11); `CLAUDE_CODE_TASK_LIST_ID=<project>-<task>` set for the session (F19); a task board page via `dashboard_page` (F20). One subtask per item when claimed; F20 and F11's Linear part sit under §13 "Out of scope", so they need the creator's go-ahead at claim time. Creator go-ahead 2026-10-11 for both; split into T441.13.1 (F8), T441.13.2 and T441.13.4 (F9), T441.13.3 (F19), T441.13.5 and T441.13.6 (F11 GitHub, Linear), T441.13.7 (F20).

#### 12. Open questions

- ~~Build or adopt (from T441.1): beads already has hash ids, a CLI, an MCP server and GitHub/GitLab sync; Backlog.md has the Markdown layout and per-repo locked ids.~~ Creator decision 2026-10-07: build rtok's own core as this card designs it (human `R12` ids, one counter per project in the store, `rtok mcp` + CLI, disk/GitHub/GitLab adapters); no beads or Backlog.md dependency.
- ~~AirTalk's own prefix: the creator's example is "AirTalk → R" (the binary is `rtok`); the product name starts with `A`. Keep `R` for this repo by override?~~ Creator decision 2026-10-07: `A` for this repo. T441.5's `rtok task init` writes `[tasks] prefix = "A"` into `.rtok.toml`; not committed earlier, since installed rtok builds older than T441.2 reject an unknown `[tasks]` table there.
- ~~Allocator in a JSON file + lock or in rtok's SQLite store?~~ Settled by T441.1: the store (§5).
- Is per-machine allocation plus the remote label check enough for several machines, or should GitHub/GitLab adapters allocate remotely (e.g. a counter issue)?
- ~~Does `closed`/won't-do need its own status, distinct from `done`? T441.1: both providers have it natively (GitHub `not_planned`, GitLab "Won't do"), so it maps cleanly; recommended yes.~~ Creator decision 2026-10-07: yes, `Status::Closed` (T441.2).
- Should the disk adapter also keep this workspace's `plan.md`/`todo.md`/`done.md` in sync, or replace them?
- How are tasks that agents create outside rtok (directly in GitHub) adopted — import on `sync`?

#### 13. Out of scope

- Implementation before the creator approves the T441.1 outcome.
- More adapters (Linear, Jira) — later, through the same trait.
- A web UI page for tasks, dependencies beyond parent/child, assignees, priorities, LLM-generated task breakdowns (Taskmaster's PRD parsing).
- An HTTP MCP transport or a hosted service.
- Replacing hosts' built-in TodoWrite/`update_plan` checklists.

Result: every milestone in §11 is done: T441.1 research (`research.md` §35), T441.2 core types, T441.3 allocator in the store, T441.4 adapter trait and disk adapter, T441.5 CLI, T441.6 MCP tools, T441.7 GitHub adapter, T441.8 GitLab adapter, T441.9 docs, T441.10 instruction line, T441.11 Projects v2 Status, T441.12 `rtok task sync`, and T441.13 split into T441.13.1 (F8 `task` on agent messages), T441.13.2 and T441.13.4 (F9 conflict and parallel markers on disk, GitHub and GitLab), T441.13.3 (F19 `CLAUDE_CODE_TASK_LIST_ID`), T441.13.5 and T441.13.6 (F11 GitHub Issues and Linear exporters) and T441.13.7 (F20 read-only board in `rtok web` and `rtok tui`). The three §12 questions still open (several machines, keeping `plan.md`/`todo.md`/`done.md` in sync from the disk adapter, adopting tasks created outside rtok) are parked as I-126 to I-128 in `ideas.md`.

Status: done 2026-10-11

Model: Claude Code / claude-opus-5-5
