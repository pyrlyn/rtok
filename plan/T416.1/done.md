Replace `render::unified_diff`, `render::file_diff`, `render::paint` and `info::human_bytes` with the crate (call sites: `config/mod.rs`, `config/validate.rs`, `plugins/memory/sync.rs`, `plugins/read/cache.rs`, `doctor/fix.rs`, `agents/mod.rs`, `agents/junk.rs`, `worktree/list.rs`, `worktree/clean.rs`). No output changes: the trycmd and installer tests stay byte-identical. Drop `similar` from `Cargo.toml` if nothing else uses it.

Check: `just check` green with no expected-output file touched.

Result: `src/diff.rs` and `bytes::human_bytes` are gone; `change_preview::{paint, unified_diff, human_bytes}` are called directly (config crate's private diff copy dropped too), `similar` left every `Cargo.toml`. `render::file_diff` stays as a three-line `paint(unified_diff(..))` because the crate has no one-call form. No expected-output file changed.
