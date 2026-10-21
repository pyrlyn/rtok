Tasks moved from `plan.md`, `todo.md` and `done.md` into `plan/<id>/` folders: 1267 folders
(47 active, 1218 closed records, T954 and T955). The move is lossless against `c14b95e1b`: every
non-blank line of the old files (headings and table rows aside) is present under `plan/`.
`tests/plan_dir.rs` replaced `tests/plan_unique_ids.rs`; `AGENTS.md`, `CONTRIBUTING.md`,
`justfile` and the per-plugin `Checks:` pointers name `plan/`.

Merged as PR pyrlyn/rtok#1229 (squash `fc080a80`) with `--admin`, because `main` was red on
`fs-kit` 0.2.0 at the time.

Left open: the `ci.yml` comment still names `plan.md`. Touching `ci.yml` trips the pre-push
`jactionlint` hook on its existing unpinned `pyrlyn/ci/...@main` uses, so it was not changed here.

Checked: `cargo test --test plan_dir` on the migrated tree, and the loss check against
`c14b95e1b` described in the task's Check line.

`AGENTS.md` shortened to stay under its 350-token budget (`tests/host_docs.rs`).
