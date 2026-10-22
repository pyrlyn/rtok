Follow-up of T441.13.2, which stores the markers on the disk adapter only. Store `conflicts_with` as `rtok:conflicts:<id>` labels and `parallel` as an `rtok:parallel` label through `src/tasks/meta.rs` (the same managed-label path as `rtok:needs:` and `rtok:p:`), read them back in the GitHub and GitLab issue-to-task mapping, drop the T441.13.2 refusal for these adapters (`stores_markers` true), and keep a user's own labels untouched. Done when `task conflict` and `task parallel` work on both with the recorded HTTP fixtures, `list`/`show`/`ready` show the markers, and `just check` passes.

Check: the recorded HTTP fixtures for both adapters pass and `just check` is green.

Result: GitHub and GitLab store `conflicts_with` as `rtok:conflicts:<id>` labels and `parallel` as `rtok:parallel`, through the managed-label path in `src/tasks/meta.rs` (`read_meta` now returns a `Meta` struct; `is_managed`, `managed_for` and `merge_labels` cover both labels). Both adapters read the markers back in the issue-to-task mapping and report `stores_markers() == true`, so `task conflict` and `task parallel` work there and `list`, `show` and `ready` show them; other adapters still refuse. A user's own labels are kept. Tests on mock-server responses in `src/tasks/github.rs` and `src/tasks/gitlab.rs` (exact save bodies, read-back, symmetric `Project::conflict`, user-label survival) and a round-trip test in `meta.rs`; the "Disk adapter only" help text is gone from the CLI, MCP and goldens; docs en/ru/uk.

Status: done 2026-10-11

Model: Claude Code / claude-sonnet-5-5
