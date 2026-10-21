**T6.3 import** · T6.1 · `src/plugins/memory/import.rs`
Do: `rtok memory import <file.jsonl>`: one note per line `{kind, title, body, ts?, project?}`. Users export their previous memory tool to that shape themselves; rtok knows no third-party schema (D6). Dedupe by sha256 of body; print inserted/skipped/malformed counts; exit 0.
Check: a 50-line fixture → 50 rows; re-import → 0 inserted, 50 skipped; one malformed line is counted, skipped, exit 0.
Status: done 2026-09-02 · Check: `fifty_then_reimport_then_malformed_exits_ok` — 50 inserted, re-import 50 skipped, extra bad line malformed=1. `make check` green. Deviation: also `cli.rs` (`rtok memory import`) and `Store::note_bodies`.
