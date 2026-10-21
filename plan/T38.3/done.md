**T38.3 e2e `memory import` + `graph index`** · P2, 2/5 · `tests/import_index_e2e.rs` (new, 132 LOC)
Do: `memory import` of a runtime-written JSONL fixture is found by `mem_search` over `mcp` stdio; `graph index` of a runtime-written two-file Rust tree is found by `symbol` over `mcp` stdio. Hermetic temp homes (`rtok-t383-`, removed at end), harness from `commands_e2e.rs`, `tools/call` shape from `graph_contract.rs`.
Check: both cases green; `just check` green.
Complexity: 2/5
Status: done 2026-09-14 · Model: OpenCode / Muse Spark 1.3
Evidence: `cargo test --test import_index_e2e` 2 passed / 0 failed (`memory_import_is_searchable`, `graph_index_finds_symbol`); fmt + clippy clean; full `just check` green (fmt-check, workspace clippy `-D warnings`, 39 test binaries ok incl. `plugins_e2e` 11 + `import_index_e2e` 2, build-min, jscpd 1.26% under threshold 2).
