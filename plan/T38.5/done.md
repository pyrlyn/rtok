**T38.5 `commands_e2e.rs` on `assert_cmd`** · P2, 2/5 · `Cargo.toml`, `Cargo.lock`, `toolchain.md`, `tests/commands_e2e.rs`
Do: add `assert_cmd = "2"` to dev-deps (already in `rust.md`, same line as ketch/cox — reuse, no new approval), record it in `toolchain.md`, rewrite `tests/commands_e2e.rs` on `Command::cargo_bin` with `cmd`/`ok`/`hook` helpers (`.assert().success()/.failure()`, `write_stdin` for `hook`) — same 9 cases, same asserts, no `std::process::Command` left.
Check: `cargo test --test commands_e2e` 9/9 green; fmt + clippy clean.
Complexity: 2/5
Status: done 2026-09-14 · Model: OpenCode / Muse Spark 1.3
Evidence: `cargo test --test commands_e2e` — 9 passed / 0 failed; `cargo fmt --check` clean; `cargo clippy --test commands_e2e -- -D warnings` clean; test file 173 LOC; `Cargo.lock` gains the resolved `assert_cmd` subtree only.
Deviation: none. (Also removed the leftover T38.4 card and a duplicated T38.2 card from `plan.md` while claiming — dead text only.)
