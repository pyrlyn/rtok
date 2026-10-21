**T6.2 SessionStart recall** · T6.1, T2.4 · `src/plugins/memory/inject.rs`
Do: inject the last 5 note titles + ids for the current project (≤ 200 tokens) through `inject` with priority 10; never bodies.
Check: fixture with 20 notes → 5 titles, ≤ 200 tokens, byte-stable across runs.
Status: done 2026-09-02 · Check: 20 notes → 6-line recall (header + 5 titles), no bodies, ≤ 200 tokens, byte-stable. `make check` green. Deviation: implemented in `memory/mod.rs` (not `inject.rs`) plus `Store::list_note_titles`.
