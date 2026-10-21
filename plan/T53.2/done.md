**T53.2 Shell completions and man page** · P3, 1/5 · `Cargo.toml`, `Cargo.lock`, `src/cli.rs`, `tests/completions.rs`, `tests/surface_parity.rs`, `tests/trycmd/completions-bash.toml`, `tests/trycmd/completions-bash.stdout`, `tests/trycmd/help.stdout`, `README.md`, `toolchain.md`

From I-20. `rtok` has a large clap surface but no completions or man page.

Do: `rtok completions <shell>` prints bash/zsh/fish/powershell completions via `clap_complete::generate`, `rtok man` prints the roff page via `clap_mangen::Man` — both generated from `Cli::command()`, so they stay byte-exact with the CLI surface. The shell is a positional `ValueEnum` (no long flag), so no D12 config key and no `config_coverage` ALLOW change; both commands are classified in `surface_parity.rs` EXEMPT as helpers (D27 gate). Workspace `rust.md` already listed both crates — no change there.
Check: `tests/trycmd/completions-bash.toml` + blessed `.stdout` (one shell), `tests/completions.rs` (assert_cmd: every shell renders non-empty output naming the binary, unknown shell refused, `man` carries `.TH`/rtok/completions markers — no `man` snapshot, the page embeds the git-sha version), regenerated `help.stdout`, README install snippet.
Status: done 2026-09-17 · Model: OpenCode / Muse Spark 1.3

Evidence: isolation worktree at 7d1e2a1 + own files only — `cargo fmt --check` green; `cargo clippy --workspace --all-targets --all-features --exclude rtok-wasm-demo-guest -- -D warnings` green; `cargo nextest run --test completions --test cli_trycmd --test surface_parity --test config_coverage` 9 passed. Full `just check` on main stays red on concurrent agents' in-progress work (graph test arity errors, windsurf unused import, transient mid-edit lib breakage) — reported, left for the owners.

Deviation: ~100 hand-written LOC but 10 files (the D27 gate, both snapshots and the docs each demand their file); `completions-bash.stdout` is 2796 generated lines, not counted. Includes the 4-line rustfmt normalization of the `wrap` EXEMPT entry — HEAD was not fmt-clean there, and without it no commit can pass `fmt --check`.
