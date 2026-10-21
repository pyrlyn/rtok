---
status: in progress
priority: P1
complexity: 3
readiness: 70%
agent: Claude Code / claude-opus-5-5
---

# T416. Shared `change-preview` crate for dry-run output

Every command that changes the disk should preview it the same way, and ketch and cox carry the same need (ketch has its own dry-run paths; cox depends on `similar` and `diffy`). The renderer moves out of `src/render.rs` into a crate with a neutral name in `packages/crates` (`pyrlyn/crates`, tracked there as T1), released to crates.io by that repo's release-plz pipeline; rtok then depends on the crates.io version, because a path outside this repository does not resolve in CI. Blocks T416.1-T416.4.

The crate renders two kinds of change. A file edit (path, before, after) is a unified diff with the `a/` and `b/` headers `render::unified_diff` prints today, or in stat mode a `path | 7 +++--` line; a removal (path, bytes, files) is `- path  12.1 GB  48 213 files`. Both modes end with a totals line: `3 files changed, 12 insertions(+), 4 deletions(-)` for edits, `15 paths, 1 204 311 files, -106.2 GB` for removals. Colour goes through owo-colors' `if_supports_color` exactly as `render::paint` does; byte sizes through the crate's `human_bytes` (the same binary units as `info::human_bytes`); everything derives `Serialize` for `--json`.

Plan: in `packages/crates` (branch `t1-change-preview`), add the crate's project files (`AGENTS.md`, `plan.md`, `todo.md`, `done.md`, `roadmap.md`, `ideas.md`, `toolchain.md`) and the crate as a workspace member, with unit tests for both modes, the totals and the colour switch; `bump.yml` gains a `package` choice and `release.yml` reads the crate from the tag, so the pipeline that releases `file-backup` releases this crate too; one PR there; then `bump.yml -f package=change-preview` publishes it. The crates.io token must allow publishing the new crate name.

Check: `cargo test` and `cargo clippy -- -D warnings` green in `packages/crates`; the crate is on crates.io.
