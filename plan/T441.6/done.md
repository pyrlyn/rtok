Sixth subtask of T441 (task adapters): `task_create`, `task_list`, `task_get`, `task_status` and `task_next` on `rtok mcp`, with the same JSON as the CLI.

Check: CLI ↔ MCP parity (same inputs, same JSON); `just check` green.

Result: `src/mcp/tasks.rs` lists the five tools beside the worktree and agent tools and calls the same `tasks::run` functions as `rtok task …`; `Project::get`, `Project::status` and `run::filter` moved there from the CLI so neither front-end keeps its own copy, and `show` now fails with `no task <id>` itself. Each call re-reads the config for the current cwd, because `roots/list` can move the server into the project after launch and `[tasks] prefix` lives in that project's `.rtok.toml`. `tests/task_cli.rs` drives the tools through `rtok mcp --call` and compares their answers with `--json`. The server entry `rtok agents install` already writes covers every host, so the tools need no install of their own; the AGENTS.md/CLAUDE.md instruction line was split into T441.10.

Status: done 2026-10-07
Model: Claude Code / claude-opus-5-5
