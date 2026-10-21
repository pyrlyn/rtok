**T6.1 notes API** · T0.3 · `src/plugins/memory/mod.rs`
Do: MCP tools `mem_save(kind, title, body, project?)`, `mem_search(query, limit=5)` → ids + titles + 120-char snippets (FTS5 `bm25`), `mem_get(id)` → full body. Project = git root name of cwd.
Check: save 3, search returns the right one first, get returns the full body.
Status: done 2026-09-02 · Check: save 3 notes, search `"walrus"` returns that title first, `mem_get` returns the full body. `make check` green. Deviation: also `store/mod.rs` (`search_notes`, `get_note_body`, `NoteHit`). MCP stdio comes in T4.1.
