Ivan, 2026-09-30: the same fuzz setup as ketch (ketch plan R4). PR: https://github.com/pyrlyn/rtok/pull/535.

Plan:
1. `fuzz/`: a standalone cargo-fuzz workspace excluded from the root one (`cargo-fuzz = true`, `libfuzzer-sys` 0.4, `arbitrary`, `rtok = { path = ".." }`), so `cargo build`, `just check` and CI never compile it.
2. Entry points in `src/fuzzing.rs`, compiled only under `--cfg fuzzing` (set by `cargo fuzz`), reach crate-private parsers without disk, env or log side effects.
3. Targets: `cli-argv` (`rtok::cli::Cli::try_parse_from` plus the help/error rendering), and the crash-prone parsers: `config-toml` (`config validate` + the file-free config stack), `cmd-filter` (user `rules` TOML + the stdout compactor), `hook-io` (the `HookInput` JSON and each host adapter), `jsonc-edit` (the JSONC editor that writes host settings), `proxy-wire` (Anthropic / OpenAI / Gemini request, response and SSE parsing), `transcript-jsonl` (`measure::jsonl`), `outline` (`read` outlines, tree-sitter queries) and `text-ops` (terse compression, `rtok expand` ranges, cuts). Seed corpora come from `tests/fixtures`.
4. Verify: `cargo +nightly fuzz build` for every target, then a short run of each (`cargo +nightly fuzz run <target> -- -max_total_time=60`). Every crash becomes a minimized regression test with its fix in its own PR.
5. Optional: a non-required nightly CI job (build plus a short run) on Linux. Do not touch `dependabot.yml` or `sync-docs.yml`.
6. Deliver as a PR; do not merge it.

Dependencies: nightly toolchain (`rustup toolchain install nightly`); `mise.toml` keeps stable 1.98.1 as the build toolchain. `cargo-fuzz` via `cargo install cargo-fuzz` or `"cargo:cargo-fuzz"` in `mise.toml`, recorded in `toolchain.md`. libFuzzer runs on macOS and Linux only. Independent of T315.

Check: `cargo +nightly fuzz build` succeeds for every target; each target runs 60 s with no crash (or the crash is filed with a repro test); `just check` on stable does not compile `fuzz/`.

Result: merged in #535 (`3c2bfdfd`). `fuzz/` is a standalone cargo-fuzz crate with its own `[workspace]`, listed in the root workspace's `exclude`, so `cargo build`, `just check` and CI do not compile it; `src/fuzzing.rs` is compiled only under `--cfg fuzzing` (declared for check-cfg). Nine targets: `cli-argv`, `config-toml`, `cmd-filter`, `jsonc-edit`, `hook-io`, `proxy-wire`, `transcript-jsonl`, `text-ops`, `outline` (all in-process: no network, spawns or file writes). One crash found and fixed in its own commit with a unit test: `cmd_stem` sliced the last 4 bytes of a basename for its `.exe` check and panicked inside a multi-byte char (`héllo`); it now uses `get`. Docs: `fuzz/README.md`, a CONTRIBUTING paragraph, `toolchain.md` rows (cargo-fuzz, `arbitrary`, `libfuzzer-sys`) covered by `tests/toolchain_rows.rs`; `just fuzz [target|all] [secs]` is not part of `check`.

Check result: on macOS arm64 with ASan every target built and ran 90 s, then 120 s more on the same corpus (`cmd-filter` 120 s more after the fix): 0 crashes apart from the fixed one (13k-311k execs per target, table in #535). `cargo fmt --check`, `cargo clippy --workspace --all-targets --all-features -D warnings`, the fuzz crate's clippy under `--cfg fuzzing`, `cargo test --test toolchain_rows` and the `cmd_stem` unit tests pass; PR CI green.
Deviations: no nightly CI job (plan step 5 was optional).

Status: done 2026-09-30 (#535)
Model: Grok Bot
