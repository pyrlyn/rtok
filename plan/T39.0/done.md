**T39.0 survey: LadybugDB replacement** · — · `src/plugins/graph/PLAN.md`, `research.md`, `Cargo.toml`, `src/store/`
Do: survey keep-SQLite / Grafeo / frozen-lbug; name winner; delete losers.
Check: SQLite only; `graph-lbug` and `graph-grafeo` gone; `just check` green; numbers archived.
Status: done 2026-09-12 · Check: Winner = SQLite. Removed feature `graph-lbug`, optional dep `lbug`, `src/store/symbols_lbug.rs`, `.cargo` `LBUG_BUILD_FROM_SOURCE`, mise `cmake` pin for liblbug. Grafeo never merged (PR #21/#22 closed). `src/store/mod.rs` is SQLite-only `symbols`. Docs: D18 amended, P39 closed, `research.md` keeps P8c + P8e tables as archive. Supersedes further lbug/grafeo work.
