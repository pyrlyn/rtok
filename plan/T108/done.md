Asked for by the creator on the T82/T93 PR. `tests/windows_ci.rs`, no new dependency (`regex`, `ignore` are already in `Cargo.toml`):

1. `rtok_exe_reserves_an_8_mib_main_thread_stack` (`cfg(windows)`) reads `SizeOfStackReserve` from the PE optional header of `CARGO_BIN_EXE_rtok` and requires ≥ 8 MiB — dropping the T93 line from `build.rs` fails one named test instead of ~100 e2e crashes.
2. `byte_compared_files_are_lf_in_the_working_tree` — no CR byte in `tests/trycmd/` or `skills/`; on a Windows checkout this is what `.gitattributes` (T82) guarantees.
3. `windows_exclusion_list_names_only_existing_tests` — every `binary(x)` in the `cfg(windows)` `default-filter` is a `tests/x.rs`, every test name is a `fn name(` somewhere in `src/`, `tests/` or `crates/`, so a rename cannot leave a line that skips nothing and T83's list cannot look non-empty by accident.

Check result (2026-09-21): macOS — 2 passed (the PE test is Windows-only); mutation: renaming `test(=cli)` to `test(=cli_renamed_away)` in `.config/nextest.toml` makes test 3 fail with `stale exclusions: ["test cli_renamed_away"]`. `rustfmt --check` and `clippy --test windows_ci -D warnings` clean. The Windows half is proved by the PR's `windows` job.
