`cargo build --tests --workspace` took 48.0 s by default and 37.45 s with `CARGO_PROFILE_DEV_DEBUG=0` (warm `target/`, 2026-09-28). CI already builds without debug info (`ci.yml`); `[profile.dev]` keeps `line-tables-only` locally for backtrace line numbers. Done = the fastest option that does not slow the interactive loop is wired into `just check`, with before/after numbers; or the task closes with the measurements and no change.

Plan: measure a realistic cycle (edit one `src` file → gate cargo steps → `cargo build` → edit → `cargo build` → gate) for (a) status quo, (b) `CARGO_PROFILE_DEV_DEBUG=0` inside the justfile's cargo recipes, (c) a custom profile inheriting `dev` with `debug = false` used through `--profile` / nextest `--cargo-profile`, with its disk cost; wire in the winner or close with the numbers.

Result: no change — nothing is a net win on macOS. Measured 2026-09-28 in one worktree (M-series, 16 CPUs, rust 1.98.1, nextest 0.9.143). Other agents kept the load at 20–40, so wall times swung 2x run to run; the comparison uses child CPU seconds (user+sys) of the gate's cargo steps (both clippy runs, `nextest run --no-run`, `build-min`), four edit→gate samples per option:

| Option | Gate CPU s (mean) | `cargo build` right after the gate | edit → `cargo build` |
| --- | --- | --- | --- |
| (a) status quo | 223, 216, 231, 241 (228) | 7.0, 6.6 | 6.2, 6.8 |
| (b) `CARGO_PROFILE_DEV_DEBUG=0` for the gate | 241, 235, 237, 240 (238, +5 %) | 6.6, 6.5 | 6.2, 6.8 |
| (c) `[profile.gate]`, `inherits = "dev"`, `debug = false` | 166, 168, 163, 182 (170, −25 %) | 7.2, 7.1 | 6.6, 6.5 |

- No option thrashes the interactive loop. Cargo hashes the whole profile into each unit's metadata and file-name suffix, so `debug = 0` and `line-tables-only` artifacts of test and check units sit side by side in `target/debug/deps` (`compute_metadata`: `unit.profile.hash(...)`; `use_extra_filename` is true for tests and check units, and for executables on non-MSVC hosts — https://github.com/rust-lang/cargo/blob/rust-1.98.0/src/cargo/core/compiler/build_runner/compilation_files.rs, checked 2026-09-28). Dependencies already have `debug = false`, so (b) shares them.
- Debug info itself costs nothing measurable here. With the target dir held fixed (`target/gate`), only `CARGO_PROFILE_GATE_DEBUG` changed, three alternating reps: `nextest --no-run` CPU 135/139/141 with `line-tables-only` against 144/145/150 without; clippy 21/24/29 against 23/27/31. On macOS the dev default is `split-debuginfo = "unpacked"` (https://doc.rust-lang.org/cargo/reference/profiles.html#split-debuginfo, checked 2026-09-28): DWARF stays in the `.o` files and the linker never copies it into the ~90 test binaries — the cost `debug = 0` removes on Linux, where CI keeps it (T261). The 48.0 → 37.45 s evidence was wall time on a loaded machine.
- The −25 % of (c) is the fresh directory, not the debug setting: `target/debug` here held 138 k files in `deps` (131 k `.o` files kept by unpacked split-debuginfo), 13 GB of `incremental`, and many rlibs/rmeta compressed by `dunnage` (T236); `target/gate` started with 2.9 k files. It erodes as the directory ages — after `dunnage run target` compressed `target/gate`, the same steps took clippy 35/34 and tests 155/152 CPU s. And (c) costs every worktree a second cold build of all dependencies (315 s wall, +6.3 GB), which the one-worktree-per-task workflow pays on every task.
- Backtraces: nextest does not set `RUST_BACKTRACE` (https://nexte.st/docs/configuration/env-vars/, checked 2026-09-28) and nothing in the repo does, so a failing test in `just check` prints the panic's `file:line` from `std::panic::Location` either way; only frames of an explicit `RUST_BACKTRACE=1` run would lose line numbers. Not a factor.
- `check` is a reserved profile name (cargo 1.98.1: "profile name `check` is reserved").

Check: numbers above (scripts in the session scratchpad, not committed); `just check` green on the unchanged tree.

Status: done 2026-09-28
Model: Claude Code / claude-opus-5-5
