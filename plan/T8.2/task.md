---
status: todo
priority: P1
complexity: 4
readiness: 0%
agent:
---

# T8.2. Clear CLI and MCP task API with deprecated aliases

Agents and people get one small, consistent API: the CLI verbs `board`, `list`, `show`,
`create`, `edit`, `move`, `claim`, `release`, `next`, `ready`, `dep`, `conflict`, `doctor`,
`prime`, and the six MCP tools `task_board`, `task_get`, `task_create`, `task_update`,
`task_claim`, `task_release`. The old subcommands (`status`, `priority`, `parallel`, `init`,
`export`, old `sync`) and MCP tools stay one release as hidden aliases that warn. Spec: "CLI",
"MCP: the agent API" and "Config" in [docs/design/task-board.md](../../docs/design/task-board.md).

Done means: `rtok task --help` lists only the new verbs; every old command and tool still
works with its old output; `tools/list` shows the six tools; MCP JSON equals CLI `--json`;
the web and TUI board work with no `[tasks]` config.

Check: `./gradlew macosArm64Test cliTest e2eTest` green, with `TaskE2eSpec` covering every new
verb, every alias and MCP-equals-CLI for the six tools.
