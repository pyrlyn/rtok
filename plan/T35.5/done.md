**T35.5 rebuild a root when the extractor changes** · T8.1 · `src/plugins/graph/index.rs`, `src/store/symbols.rs`, `src/store/symbols_lbug.rs`, a migration
Do: a fingerprint of the extractor — every tags query string, the tree-sitter grammar crate versions, an `INDEX_VERSION` bumped when `scoped` changes — stored per root. A mismatch drops the root's rows and indexes it cold; a match changes nothing. Today a query change (T8.2's `RUST_SCOPED_CALL`, say) reaches only files edited afterwards.
Check: `reindexes_when_extractor_fingerprint_mismatches` — 3 files, tamper fingerprint to deadbeef, `read == indexed == 3`, next run `read == 0`. Fingerprint = sha256(`INDEX_VERSION` || tags queries including `RUST_SCOPED_CALL`). SQLite table `extractor` (`0011.sql`). `graph-lbug` keeps the same `Store` methods. `graph::index` 11/11.
Complexity: 3/5
Deviation: Host trait + `plugin.rs` + `symbols_lbug.rs` + `outline.rs` `pub(crate)` `RUST_SCOPED_CALL` — more than 3 files because both Store backends must compile and the hook `Host` needs the methods.
Status: done 2026-09-11 · Model: Composer 2.5
