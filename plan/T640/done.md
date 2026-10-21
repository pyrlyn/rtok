The creator's call (2026-10-12): every project takes its file and directory helpers from one shared crate, `fs-kit` in `packages/crates` (crates-packages PR #57), instead of its own copies. Line caps lifted by the creator for this task.

Plan:
1. Depend on `fs-kit` (a path to the crates checkout until it is on crates.io, then the version); drop the direct `dunce` dependency.
2. Duplicates go: `rtok-store/src/paths.rs` re-exports `fs_kit::path`; `src/fs.rs` `normalize` and `same_dir`; the junk walk (`disk_usage_until`, `Usage`) from `fs_kit::tree`; `rtok-agent-sdk` `write_atomic`/`link_target`/`clear_readonly` and `copy_dir` from `fs_kit::write` and `fs_kit::tree`; completions `read`/`write_if_changed`.
3. Every `dunce::` and `std::fs::canonicalize` call goes through `fs_kit::path`.
4. Left for later slices: the `_backup` folder with pruning (`rtok-agent-sdk` `backup`) and `info.rs` `dir_usage` (apparent size, a different number from disk usage) stay until `fs-kit` grows those variants; plain `std::fs::write`/`create_dir_all` calls move subsystem by subsystem.
5. `fs-kit` 0.1.0 is on crates.io, so the dependency is the registry version (`fs-kit = "0.1"`), not a path.

Check: `just check` green on `fs-kit` 0.1 from crates.io; `fs-kit` unit tests cover each moved helper (atomic write through a symlink, permissions kept, `if_changed`, tree usage and copy, path folding), and the existing rtok tests for `paths`, `junk`, completions install and the agent SDK pass unchanged.

Result: rtok takes paths, atomic writes, tree usage and copies from `fs-kit` 0.1 on crates.io (pyrlyn/crates-packages#57); the copies in `rtok-store` paths, `src/fs.rs`, the junk walk, the agent SDK and completions are gone, and `dunce` is no longer a direct dependency (pyrlyn/rtok#998).

Status: done 2026-10-12
Model: Claude Code / claude-opus-5-5
