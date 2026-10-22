Use the local `shell-completions` crate in `packages/crates/rust`. Preserve rtok scripts and ketch package-name completion and installation behavior. Add `cox completions <shell>` without loading configuration, credentials, telemetry or a session.

Execution plan: establish existing completion tests; extract the clap generator and Clink implementation with regression tests into the shared crate; connect consumers through local Cargo paths; update dependency inventories; run focused tests, formatting and clippy. Application-specific installation policies and ketch dynamic package lookup stay with their owners. Done when generation has one owner and the three CLI surfaces pass their checks.

Validation: 23 focused tests passed; all six generated scripts are byte-identical to the pre-extraction baseline. Formatting and production clippy passed. `just check` passed workspace clippy and 2779 tests before unrelated cli_trycmd logging snapshots failed (completion snapshots passed; 8 skipped, 1261 not run by fail-fast).

Reused `clap_complete` and the existing rtok Clink generator. No replacement library or custom generator was introduced. Local path dependencies resolve in the standard apps/packages layout.
