---
status: in progress
priority: P1
complexity: 5
readiness: 10%
agent: Claude Code / claude-opus-5-5
---

# T8. Task board on files with two-way remote sync

The creator asked to rework `rtok task`: by default it works on files that form a task board
(one folder per column: `todo/`, `in-progress/`, `done/`, `closed/`); a clear API connects the
board to GitHub Issues and Projects, GitLab, Linear or Jira with two-way sync; agents get a
clear MCP and CLI API and skills that teach it. The old subcommands, MCP tools and config keys
stay as hidden aliases for one release, and `rtok task migrate` converts old `tasks/` files.

Today the files layout has no columns, the only way to change the store is `rtok task init
--adapter`, which migrates nothing, `export` is one-way and stateless, the twelve MCP tools
cover half the CLI, and the skills ship from the Rust release and have drifted. The design,
with the decisions behind it, is [docs/design/task-board.md](../../docs/design/task-board.md).

Done means: T8.1 to T8.7 are closed, a new project gets a column board with no config, and
`rtok task remote set <tracker>` plus `rtok task sync` keep the board and the tracker in step
both ways for all four trackers.

Check: `./gradlew macosArm64Test cliTest e2eTest` is green on `main` after the last subtask,
and an e2e run moves a card on the board and on a fake tracker and sees both sides agree.

## Subtasks

| Id | Scope | Needs |
|---|---|---|
| T8.1 | Board layout, shared root, `migrate`, `board`, disk-layer fixes | |
| T8.2 | CLI verbs and aliases, six MCP tools and aliases, `[tasks]` config, `doctor`, `prime`, web board on by default | T8.1 |
| T8.3 | Sync engine, GitHub Issues + Projects v2 two-way, `remote`/`sync`/`push`/`pull`, auto mode | T8.1, T8.2 |
| T8.4 | GitLab remote | T8.3 |
| T8.5 | Linear remote | T8.3 |
| T8.6 | Jira remote | T8.3 |
| T8.7 | Skills in this repo, `docs/tasks.md`, README, PORTING, plan README decision | T8.2, T8.3 |

Each subtask is its own branch and PR (`T8.N: <title>`), based on `main` after the ones it
needs have merged.
