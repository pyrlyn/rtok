Fourth subtask of T441 (task adapters): the `TaskAdapter` trait every backend implements, and the offline `disk` adapter with archive on done.

Check: create/list/get/status/archive on a temp dir; done tasks are never listed; `just check` green.

Result: `src/tasks/adapter.rs` — `TaskAdapter` (`create`, `list`, `get`, `write_status`, `max_id`), `Filter` (active tasks by default; `all`, explicit statuses, `parent`) and `set_status`, the one place that refuses to finish a parent with active subtasks unless `force`, naming them. The card's separate `archive` is `write_status` to `done`/`closed`, since every adapter archives as part of the status change. `src/tasks/disk.rs` — one `<id> - <slug>.md` per task under `[tasks.disk] dir`, YAML front matter (`id`, `title`, `status`, `parent`, `created_at`, `updated_at`; `serde-saphyr`, already a dependency) and the description as the body; writes go through `rtok_agent_sdk::write_atomic`; done and closed tasks move to `<dir>/done/` and back when reopened, new file first; files that are not `<id> - <slug>.md` are left alone, and a broken task file is named in the error. Slugs are lower-case ASCII, at most 48 characters, so a title cannot reach outside the directory.

Status: done 2026-10-07
Model: Claude Code / claude-opus-5-5
