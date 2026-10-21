Creator request (2026-09-27): a separate command for tests with coverage; the plain one without. Coverage lived only inline in `.github/workflows/sonarcloud.yml`, with `cargo-llvm-cov` installed by a CI action, so it could not be run the same way locally.

Result: `just test-cov` (`justfile`) adds `llvm-tools-preview`, runs `cargo llvm-cov nextest --workspace` with the same thread count as `just test`, writes `coverage/lcov.info` (ignored by git), prints a per-file summary, then runs `dunnage`; extra args go to nextest (`just test-cov -E 'test(formatters)'`). `just test` is unchanged and has no coverage. `cargo-llvm-cov` 0.9.1 is pinned in `mise.toml`; the SonarCloud job calls `just test-cov` instead of its own install steps and command. `toolchain.md`, `CONTRIBUTING.md`, `docs/sonarcloud-setup.md` and the shared `rust.md` updated.

Check: `just test-cov -E 'test(every_formatter_arm_has_a_golden) | test(ten_families)'` — 2 passed, `coverage/lcov.info` written, TOTAL row printed; `cargo nextest run -p rtok --test toolchain_rows --test plugin_plans` green.

Status: done 2026-09-27
Model: Claude Code / claude-opus-5-5
