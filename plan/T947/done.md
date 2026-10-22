On 2026-10-20 every page of the creator's `rtok web` stayed on "Loading…" for 2+ minutes. The
snapshot stack was `snapshot_shared → frame → model::snapshot → stats_skills → stats_report →
measure::stats::collect`: `stats_skills` ran the transcript scan inline (2065 JSONL files,
3.7 GB for `30d`; `rtok stats --since 30d` takes 131 s), and its 30 s cache was shorter than
one scan, so a stale entry blocked the tick again.

Done: `stats_skills` (`src/model/mod.rs`) goes through the existing `Background` cache with its
own `STATS_TTL` (5 minutes). A snapshot ships the last known scan, or "reading transcripts…"
before the first one lands; a cached scan of another `transcripts_dir`/`since` is dropped. On
the creator's transcripts a debug build sent the first `/ws` frame in 0.7 s with a warm doctor
cache; the scan landed on a later tick.

Check: `model::tests::stats_skills_never_waits_on_the_transcript_scan` passes a scan that blocks
and asserts the call returns within 1 s with the placeholder, then returns the scan once it
lands (timed out before the fix); `tests/stats_model.rs` polls for the scan instead of
expecting it in the first snapshot.

Status: done 2026-10-20
Model: Claude Code / claude-opus-5-5
