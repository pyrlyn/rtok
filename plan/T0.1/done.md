**T0.1 cargo project** · — · `Cargo.toml`, `src/main.rs`
Do: `cargo init --name rtok`; binary name `rtok` via `[[bin]]`; clap derive with subcommands `hook`, `mcp`, `proxy`, `stats`, `bench`, `doctor`, `setup`, `run`, `expand`, `plugins` (all stubs printing "not implemented", exit 0). Rust pinned via `mise.toml` (no `rust-toolchain.toml`). `.gitignore`, `git init`, first commit.
Check: `cargo run -q -- --version` → `rtok 0.1.0`; `cargo run -q -- plugins` → exits 0.
Status: done 2026-09-01 · `rtok 0.1.0`, `plugins` exit 0 · commit `T0.1: cargo project`.
