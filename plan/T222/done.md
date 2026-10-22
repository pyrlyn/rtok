Found 2026-09-22 in the docs pass: `toolchain.md` claims to list direct packages from the manifests but misses `jsonc-parser` (`Cargo.toml:138` — also the only neighbouring dep without its one-line reason comment; used at `src/agents/zed/mod.rs:151`), `windows-sys` (`crates/rtok-sys/Cargo.toml:13`) and five wasm crates in `crates/rtok-webui/Cargo.toml:18-32`. The "no new dependency without a row" rule cannot be enforced against an incomplete table.

Plan: one `toolchain.md` cargo row per missing crate ("Why here" citing the task) plus the missing `jsonc-parser` reason comment in `Cargo.toml`; update workspace-root `rust.md` in the same change. No version changes.

Check: `tests/toolchain_rows.rs` (pattern of `tests/config_coverage.rs`) — the set of `[dependencies]`/`[dev-dependencies]` names across all workspace manifests minus path crates equals the `toolchain.md` cargo-table names (fails on `main` today with exactly these seven); `just check` green.

Do (2026-09-23): `toolchain.md` cargo rows for `jsonc-parser`, `windows-sys`, `console_error_panic_hook`, `js-sys`, `wasm-bindgen`, `wasm-bindgen-futures`, `web-sys`; the missing reason comment on `jsonc-parser` in `Cargo.toml`; workspace `rust.md` rows added. No version changes. Check: `tests/toolchain_rows.rs::toolchain_rows_cover_manifests` (manifest deps minus path crates == `toolchain.md` cargo rows, both directions) green.
