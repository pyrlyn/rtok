Found 2026-09-26 continuing the local Windows `just check` from T273: `-D warnings` also fails on test code CI never lints this way — the `read` symlink-escape test returns early under `#[cfg(not(unix))]` leaving the rest unreachable and `link` unused on Windows; the `otel.rs` flush-trace helpers and Cursor's `MISSING_RTOK_NOTE` serve Unix-gated tests only; and two `assert_eq!(.., true/false)` in `read` now trip `bool_assert_comparison`.

Plan: move the unix body of the escape test into one `#[cfg(unix)]` block (non-unix keeps only the outside-file cleanup), gate the four trace helpers and the note const `#[cfg(unix)]`, and spell the two boolean asserts as `assert!` / `assert!(!..)`.

Check: `cargo clippy --workspace --all-targets --all-features --exclude rtok-wasm-demo-guest -- -D warnings` green on Windows (twice, 2026-09-26). The rest of the gate runs in CI on the branch — the creator's call, local time is not spent re-running it; this lint class is invisible to CI anyway, since the ubuntu lint jobs analyze no cfg(windows) code.

Status: done 2026-09-26
Model: ZCode / glm-5.3
