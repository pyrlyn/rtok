---
status: in progress
priority: P0
complexity: 1
readiness: 90%
agent: Claude Code / Sonnet 5.5
---

# T953.1. Build `main` again: `fs-kit` `clone` is not on crates.io

T953 (#1228) set the workspace dependency to `fs-kit = { version = "0.2.0", features = ["clone"] }`. crates.io has only `fs-kit` 0.1.0, `Cargo.lock` pins 0.1.0, and `cargo metadata` fails, so every CI job on every PR is red. pyrlyn/crates merged the `clone` feature (T49, #112, `afa6512`) without a version bump, and its release-plz run has not cut 0.2.0. Until that release ships, depend on the T49 merge commit by `git` + `rev` (the crate's own code, no copy in rtok), regenerate `workspace-hack` with `cargo hakari generate`, and swap back to `version = "0.2.0"` once crates.io has it.

Plan: edit the `fs-kit` line in `Cargo.toml`, regenerate `Cargo.lock` and `workspace-hack/Cargo.toml`, run `just check`, open the PR.

Check: `cargo metadata` and `cargo hakari verify` succeed and `just check` is green.
