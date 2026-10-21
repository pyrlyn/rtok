**T55.11 `expand` never freezes the owning session's pointer** · P2, 3/5 · `src/store/mod.rs`, `src/expand.rs`, `src/plugin.rs`, `src/plugins/archive/mod.rs`, `tests/{proxy,report}.rs`

From review 2026-09-17, second pass (reproduced, then fixed). Every `expand` caller ran under a session that owns no decisions (CLI session `expand`, MCP `mcp-<pid>`), while `mark_expanded` filtered `WHERE session = ?` — it matched 0 rows, pointers were rewritten forever, toon attribution never fired, and the expand-rate honesty metric read 0 %.

Do: freeze and attribute by `archive_id` alone — `Store::mark_expanded(archive_id)` and `Store::live_zone_pointer(archive_id)` (`ORDER BY tool_use_id, session` for a deterministic pick). One expand freezes every session following that pointer: those sessions start receiving the original (more tokens; never wrong bytes — the blast radius the card sanctioned, because no expand surface can know the writer's session).
Check: store `expand_freezes_every_session_pointing_at_the_archive` (rewritten from the T45.3 per-session twin), expand `cli_expand_freezes_the_owning_sessions_pointer` + `expand_measurement_attributes_toon_pointers`, proxy e2e `proxy_expand_freezes_the_pointer_for_the_next_request` (the post-expand request carries the original turn-1 block whole while the un-expanded turn-2 pointer stays).
Status: done 2026-09-17 · Model: ZCode / GLM-5.3
Evidence: `just check` green — 841/841 tests, fmt, clippy `-D warnings`, build-min, jscpd. The load-sensitive `tests/otel.rs::hooks_stay_fast_with_an_unreachable_endpoint` failed twice at load avg 14–23 (p95 212 ms vs the 200 ms budget) from parallel agent work, passed in isolation and in the final full run; the diff touches no hook/otel path.
Deviation: none.
