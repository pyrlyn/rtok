Fifth subtask of T441 (task adapters): `rtok task create/list/show/status/next/init`, with `--json`.

Check: the commands work end to end on the disk adapter in a temp checkout; `just check` green.

Result: `src/tasks/run.rs` holds what the commands do, so T441.6's MCP tools call the same functions: `Project::open` (git root, `project_key`, the prefix from `[tasks] prefix` or the project name, the adapter; `github`/`gitlab` say they are not built yet), `create` (checks the parent exists, raises the store counter past every id the adapter holds at that depth, then allocates), `show` (task plus subtask ids), `next` (lowest open task with no active subtask), and `init`, which writes `[tasks] adapter`/`prefix` into `<git root>/.rtok.toml` with `toml_edit`, keeping the rest of the file. `src/cli.rs` adds `rtok task …`; `--body-file -` reads stdin. `[tasks] adapter` is validated against the same `ADAPTERS` list. The card's `create --status` is left out (a new task is open; `status` sets the rest), and `sync` stays with T441.7. `tests/task_cli.rs` runs the binary from init to done, including the parent refusal and a hand-deleted file whose id is not reused. README command rows and the `docs/config.md` flag table (en/ru/uk) list the commands.

Status: done 2026-10-07
Model: Claude Code / claude-opus-5-5
