`research.md` §28.4 F11, creator go-ahead 2026-10-11. A project on the disk adapter mirrors its tasks one way into GitHub Issues; the disk files stay the source of truth and the exporter never reads issues back into tasks. `rtok task export github [--dry-run] [--json]` creates a missing issue (title `<id>. <title>`, labels `rtok` and `rtok:<id>`, the managed labels from `meta.rs`), updates title, body and labels when they drift, and closes the issue for `done` (`completed`) and `closed` (`not_planned`). Config `[tasks.export.github] repo = "owner/name"`, token lookup and pacing from `src/tasks/remote.rs`, issue mapping reused from `src/tasks/github.rs`.

Done when: a second run against the same state sends no write; `--dry-run` prints the planned creates, updates and closes and sends nothing; recorded HTTP fixtures (no live network) cover create, update, close and a re-run; `just check`.

Check: the exporter's fixture tests, the trycmd help golden and `just check`.

Result: `rtok task export github [--dry-run] [--json]` mirrors disk tasks one way into GitHub Issues through the `github` task adapter, so labels, `<id>. ` titles, sub-issue links and close reasons are exactly what that adapter writes (`GithubAdapter::save` now delegates to `patch`, and only the exporter writes title and body). `src/tasks/export.rs` holds the `Exporter` trait, the diff of disk tasks against the tracker, `run` and the report; `src/tasks/export/github.rs` is the backend; `[tasks.export.github] repo` in `crates/rtok-config`. A missing issue is created, drift in title, body, status, priority, assignee or blockers is patched, done closes as `completed`, closed as `not_planned`, an active task reopens; a finished task with no issue is skipped unless it is the parent of an active subtask. A re-run with nothing changed sends no write; errors name the backend, the task and the URL, never the token. Tests on httpmock: create then idempotent re-run, update, close, reopen, dry-run, in-progress labelling, failure message; `config_coverage`, `surface_parity`, `task_cli` and the trycmd goldens; docs en/ru/uk.

Status: done 2026-10-11

Model: Claude Code / claude-sonnet-5-5
