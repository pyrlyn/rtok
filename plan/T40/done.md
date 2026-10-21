**T40 drop `demon list` and `demon update`** · P2, 1/5 · `src/cli.rs`, `src/demon.rs`, `tests/demon.rs`, `tests/surface_parity.rs`, `config/default.toml`
Do: both verbs duplicated another one. `list` was `status` over every service; `update` was `restart` plus a line comparing the binary *path*, which never changes when `ketch install` replaces the binary in place. Removed both, the `State.exe` field only `update` read, and their surface-parity rows; `status` with no names now shows every service. Approved by the creator 2026-09-14.
Check: `rtok demon list` / `rtok demon update` are clap errors; `rtok demon status` with nothing running prints all three services as stopped; `cargo test --test demon --test surface_parity --test config_coverage`; `just check`.
Complexity: 1/5
Status: done 2026-09-14 · Model: Claude Code / claude-opus-5
Evidence: `demon list` and `demon update` exit 2 (unrecognized subcommand); `demon status` rc=0 lists proxy/mcp/web stopped; `demon status proxy` rc=0; `demon`, `surface_parity`, `config_coverage` green; fmt + clippy clean. `cli_trycmd` `version` fails on `main` before this change (fixture says 0.1.0 after the v0.1.1 release) — fixed by PR #26, not here.
Deviation: 5 files instead of ≤3 (two are test tables, one a config comment); net −30 LOC.
