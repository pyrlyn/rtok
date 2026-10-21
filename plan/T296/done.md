`tests/cmd_golden` guards every `rules/default.toml` family (T240) but not the Rust formatters in `src/plugins/cmd/formatters.rs` `format()`: `jest`, `vitest`, `tree`, `cargo clippy` and `go test` had no golden, so a regression in them saved nothing and failed nothing.

Result: goldens `jest` (measured 80 %, floor 75), `vitest` (78 → 73), `tree` (71 → 66), `cargo_clippy` (68 → 63), `go_test` (43 → 38); floors are the measured saving minus 5. New unit test `every_formatter_arm_has_a_golden`: a list mirroring the `format()` arms, tied to `FORMATTER_STEMS`, fails when an arm has no golden that reaches it through `family_argv` and gets `Some` from `format()` (checked by hiding `tree.in`).

Check: `cargo nextest run -p rtok --lib cmd::formatters` 17/17; `cargo clippy -p rtok --lib --tests -- -D warnings` clean.

Status: done 2026-09-27
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
