**T38.2 e2e plugin matrix** · P1, 3/5 · `tests/plugins_e2e.rs` (new, 199 LOC)
Do: one e2e case per catalogue plugin through its surface — `cmd` via `run`, `read`/`graph`/`memory` via `mcp` stdio, `proxy`/`archive`/`toon` via in-process proxy (`httpmock` upstream), `inject`/`guard` via `hook` stdin, `measure`/`compress` via `stats --json`. `Measurement` rows asserted where owed (cmd, read `dedup`, graph `cap`, inject `inject`, guard `guard`, archive/toon counts, compress `summary` in stats JSON); surface behavior asserted where no row is owed (measure reports, memory round-trip, proxy `usage` row). Hermetic temp homes (`rtok-t382-`, `Drop` cleanup), binary harness copied from `commands_e2e.rs`.
Check: 11 cases green; `just check` green.
Complexity: 3/5
Status: done 2026-09-14 · Model: OpenCode / Muse Spark 1.3
Evidence: `cargo test --test plugins_e2e` 11 passed / 0 failed; full `just check` green (fmt-check, workspace clippy `-D warnings`, 39 test binaries ok incl. `plugins_e2e` 11 + `import_index_e2e` 2, build-min, jscpd 1.26% under threshold 2).
