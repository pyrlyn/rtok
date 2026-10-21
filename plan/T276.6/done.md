`with_loader`, `render::loader` and `render::spinner` are gone. In-process waits use `ProgressRunner::wait`, and `graph index` steps a `Batch` over files. `indicatif` is a dependency of `rtok-sys` only. `clippy.toml` bans `std::process::Command::new`; `just lint` denies that lint on libraries and binaries and allows it only for the all-targets pass that includes tests. The allow is only under `crates/rtok-sys`. `CONTRIBUTING.md` and `CHANGELOG.md` say to run external programs through `ProgressRunner`.

Landed in `7067aab3` (#1057). This card only records that the check already holds on main. The epic stays open until its own card moves.

Check: `just check` fails on a stray `Command::new` outside `crates/rtok-sys`.

Check result: `the_lint_gate_bans_command_new_outside_rtok_sys` checks the `clippy.toml` ban, the `--lib --bins` deny in `just lint`, and that the only `allow(clippy::disallowed_methods)` lives under `crates/rtok-sys`. `only_rtok_sys_depends_on_indicatif` checks that `indicatif` is not used anywhere else.

Status: done 2026-10-19
