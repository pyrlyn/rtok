`rtok task` reads and writes the `plan/<id>/` layout. PR: https://github.com/pyrlyn/rtok/pull/1233

What was done:

- `src/tasks/plan_dir.rs`: a folder-layout adapter next to `DiskAdapter`. It edits only the `status`, `priority` and `agent` lines of `task.md`, writes `done.md` for done and closed tasks (`Dropped:` first line means closed), skips folders that are not task ids, and tolerates old finished tasks with no front matter. Blockers, conflicts, the parallel marker, priority 4 and an in-progress task without an agent are refused.
- `[tasks.disk] layout = "auto" | "files" | "folders"` selects the adapter in `Project::open`; `auto` follows what `dir` holds, so no existing project changes. No migration command: the old layout keeps working and nothing is rewritten.
- `rtok task status <id> done|closed --report <text|->` and MCP `task_status` `report`; new `TaskAdapter::write_report` (other adapters refuse).
- `TaskId` depth 2 -> 3, so `T385.9.3` parses.
- The `tests/plan_dir.rs` rules moved to `tests/common/plan_rules.rs` and are run over the folders the adapter writes.
- Docs (en/ru/uk) and the trycmd snapshots updated.

Not done: no `.rtok.toml` is committed. A root `.rtok.toml` with `[tasks] prefix = "T"` and `[tasks.disk] dir = "plan"` makes `rtok task list` list the 49 folders without `done.md`, but it breaks two tests that read the project layer from the cwd (`model::tasks::tests::configured_follows_the_selected_config_file`, `a_layer_that_sets_tasks_makes_the_project_configured`). Making them hermetic is a follow-up.

Checked: unit tests for create, claim and close in a temp `plan/` that pass `plan_rules::folder_errors`; with a temporary `.rtok.toml`, `rtok task list` printed exactly the 49 folders without `done.md`; create, subtask, `--report` from a flag and from stdin, and the blocker refusal run in a throwaway repo; `just lint` and `cargo nextest run --workspace` (see the PR for the two environment-only failures).
