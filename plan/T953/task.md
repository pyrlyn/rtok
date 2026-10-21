---
status: in progress
priority: P1
complexity: 3
readiness: 80%
agent: Claude Code / opus-5.5
---

# T953. Seed a new worktree's build caches by copy-on-write clone

Agents seed a new worktree's `target/` by hand with `cp -c -R <sibling>/target ./target` (APFS only, ~17 MiB instead of ~8 GB, no dependency rebuild) and then `touch build.rs crates/*/src/lib.rs` because `cp` stamps the copies newer than the fresh checkout. The creator wants this built in on macOS, Linux and Windows, on by default (2026-10-21; overrides I-99's "measure first"): `rtok worktree add`, MCP `worktree_add` and the `WorktreeCreate` hook (all through `claim::add`) seed each `[worktree] seed_dirs` entry (default `target`, `node_modules`; a bare name also matches one level down, e.g. `web/node_modules`) from the sibling checkout of the same repository that built it most recently. Files are cloned with `fs_kit::tree::clone_tree` (pyrlyn/crates T49: APFS `clonefile`, `FICLONE`, ReFS/Dev Drive block cloning, a plain copy elsewhere) and keep their mtimes, so cargo rebuilds what the checkout changed without a `touch`. A failed seed warns, removes its partial copy, and the worktree is still created. `[worktree] seed = false` or `--no-seed` turns it off. Done: tests for source choice, skip rules, clone-or-copy counts and the fail-open path; docs (`docs/agents-and-worktrees.md`, `docs/ru`, `docs/uk`), `toolchain.md`, workspace `rust.md`; `just check` green.

Plan: `crates/rtok-config` `[worktree]` gains `seed` and `seed_dirs`; new `src/worktree/seed.rs` (positions, freshest source, clone, fail-open cleanup) called from `claim::add` after the symbol index copy; `--no-seed` on `rtok worktree add` through `ops::add`; workspace `fs-kit` with feature `clone`, at the version release-plz publishes for T49.

Check: `src/worktree/seed.rs` unit tests (positions, freshest source, clone or copy counts with mtimes kept, fail-open cleanup), `just check`, and one real `rtok worktree add` on this Mac with `df` before and after.
