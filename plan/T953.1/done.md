Fixed the broken `main`: `fs-kit` is now a `git` dependency pinned to `afa651209f2b99b03058ed63bd4e62477737dbdb` of pyrlyn/crates (T49, the merge that added the `clone` feature), because `0.2.0` was never published. `Cargo.lock` resolves it with `reflink-copy`; `cargo hakari generate` added `windows` to `workspace-hack`. No rtok source changed, so T953 keeps its `fs_kit::tree::clone_tree` use.

Follow-up for the creator: release `fs-kit` 0.2.0 through the pyrlyn/crates release-plz pipeline (the `feat(fs-kit)` commit is on `main` there, version still 0.1.0), then set the workspace line back to `version = "0.2.0"`.

Checked: `cargo metadata`, `cargo hakari verify` and `just check`.
