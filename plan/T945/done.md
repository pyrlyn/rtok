Found while fixing T943 (PR #1189): with a cold `<RTOK_HOME>/transcripts-cache.json` (first
start, new `RTOK_HOME`) the first `/ws` snapshot of `rtok web` waited ~2 minutes. Stack:
`model::snapshot → doctor_for_snapshot → doctor::page → doctor::read_share → doctor::transcripts
→ measure::transcript_cache::scan → jsonl::parse_path` — the doctor probe parsed every
transcript inline on the tick.

Done: `doctor_for_snapshot` (`src/model/mod.rs`) goes through the existing `Background` cache
on `DOCTOR_SNAPSHOT_TTL`; a cached report for other doctor paths is dropped
(`Background::get_keyed`, which T943's `stats_skills_from` can reuse). `Snapshot` gains
`doctor_pending`: true until the first probe lands, with no "doctor probe failed" banner
meanwhile. The tui Doctor tab and the web Doctor page (and the Overview checks) say "running
doctor…" while pending. `cfg!(test)` stays synchronous. On the creator's transcripts, with a
scratch `RTOK_HOME` and T943 applied, a debug build sent the first `/ws` frame in 0.11 s
(171 s with this fix alone, since the stats scan still blocked); the doctor report landed
121 s later on a later frame.

Check: `model::tests::doctor_for_snapshot_never_waits_on_the_probe` passes a probe that blocks
and asserts the call returns within 1 s as pending, then returns the report once it lands
(timed out before the fix); `tui::view::tests::doctor_tab_waits_for_the_first_probe`; web
`ops.test.tsx` and `pages.test.tsx` pending cases; `just check`.

Status: done 2026-10-20
Model: Claude Code / claude-opus-5-5
