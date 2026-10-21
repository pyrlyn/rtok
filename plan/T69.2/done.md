From the graymatter gap review (`research.md` §14). graymatter ranks recall by vector + keyword + recency with a deterministic 30-day half-life and per-signal "receipts"; facts fade without access and are never hard-deleted. rtok: SessionStart recall is the newest `recall_titles` (5) ids of the project; `mem_search` is bare BM25 (`search_notes`) or RRF over BM25 + hash-embed when `embed.enabled` — a note used in every session for a month drops out of recall the moment five newer notes exist, and a stale note ranks as high as a fresh one.
Done when:
1. Evidence first: `rtok memory status` (T69.4) on this machine — notes per project and how many projects hold more than `recall_titles` live notes — recorded in `research.md` §14. If no project does, the order never matters and the card closes with the number.
2. A migration adds `notes.uses INTEGER NOT NULL DEFAULT 0` and `notes.last_used INTEGER NULL`; `mem_get` and every `mem_search` hit bump them; inclusion in a SessionStart recall does not (the hook path writes nothing per note, D13).
3. `[plugins.memory] half_life_days = 0` — 0 keeps today's id-desc order with byte-identical output; N > 0 scores `ln(1 + uses) × 0.5^(age_days / N)`, ties by id desc. Recall and search share one scoring function; search re-ranks the top `3 × limit` BM25 / RRF hits so FTS5 still does the retrieval. `NoteHit` gains `score` and `age_days` (graymatter's receipts), so the MCP result shows why a hit ranked. Decay ranks, never prunes (D4).
4. Tests: a fixture of 20 notes where a 60-day-old note used 10× outranks a fresh unused one only when `half_life_days > 0`; `half_life_days = 0` reproduces the T6.2 recall bytes exactly; scoring is deterministic under a frozen clock.
5. The default stays 0 until T69.3 shows a higher hit rate on the 100-session run without more recall bytes; the card records the numbers either way. `docs/config.md` row in the same commit (D12).

**Result (2026-09-18).** Installed `rtok 0.1.1` has no `memory status`. T69.4's `memory_note_aggs` query (`kind NOT LIKE 'checkpoint%'`) on `~/.rtok/rtok.db` returned **0 live notes** and **0 projects**. `recall_titles = 5`. No project holds more than five live notes, so ranking order never matters: no migration, no scorer, no `half_life_days` key. The store still has 25 `checkpoint` rows (project `rtok`, title `compact`); T69.4 excludes them. Evidence: `research.md` §14.1.

**Check:** docs-only close; no ranking code.

---
