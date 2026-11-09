---
status: todo
priority: P1
complexity: 4
readiness: 0%
agent:
---

# T8.2. Clear CLI and MCP task API with deprecated aliases

Agents and people get one small, consistent API over the `kotlin-taskboard` library: the CLI
verbs `board`, `list`, `show`, `create`, `edit`, `move`, `claim`, `release`, `next`, `ready`,
`dep`, `conflict`, `batch`, `watch`, `doctor`, `prime`, and the eight MCP tools `task_board`,
`task_get`, `task_create`, `task_update`, `task_claim`, `task_release`, `task_batch`,
`task_changes`. Every write takes `if_rev`, `base_rev` and `key`; every output carries `rev`
and the board `seq`; errors keep their codes. The old subcommands (`status`, `priority`,
`parallel`, `init`, `export`, old `sync`) and MCP tools stay one release as hidden aliases
that warn. Spec: "CLI", "MCP: the agent API", "Concurrency" and "Config" in
[docs/design/task-board.md](../../docs/design/task-board.md).

Done means: `rtok task --help` lists only the new verbs; every old command and tool still
works with its old output; `tools/list` shows the eight tools; MCP JSON equals CLI `--json`;
the web and TUI board work with no `[tasks]` config; parallel CLI and MCP clients racing on
one board never lose a write or claim the same task twice.

Check: `./gradlew macosArm64Test cliTest e2eTest` green, with `TaskE2eSpec` covering every new
verb, every alias, MCP-equals-CLI for the eight tools, and a race test (several `rtok task`
processes claiming, editing and batching at once).
