Cloud review finding (2026-10-08, issue #628): `src/store/mod.rs` was 5,275 lines and belonged in `crates/rtok-store`. Already done before the review was triaged: #907 (#628) extracted the SQLite store into `rtok-store`, and #910 (#631) stopped it writing the terminal. `src/store/mod.rs` is now a 120-line re-export with the housekeeping warning hand-off to `stdio::stderr`. Nothing left to move.

Check: `wc -l src/store/mod.rs` (120 at `26fe6c18`); `git log -- src/store/mod.rs`.

Result: closed without code; the findings row leaves `plan.md`.
Status: done 2026-10-10
Model: Claude Code / opus-5.5
