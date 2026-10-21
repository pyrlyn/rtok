I-86 (strip or pointer prior reasoning blocks on replay) has no number. First read the provider docs for what is already dropped server-side from earlier turns and cite it in the row. Then measure in `measure::stats` (same walk and unique-`message.id` rule as the other rows): bytes of `thinking` content blocks in assistant messages, per session and as a share of session input across the turns that re-send them. Gate 3 % of session input: above → promote I-86 to a task with an A/B Check; below → move I-86 to Rejected with the row as evidence.

Plan: count `thinking` blocks in `src/measure/stats.rs` (same unique-`message.id` walk), text + JSON line, fixture unit test; run `rtok stats --since 30d`, add the dated row to `research.md` §2, update I-86 in `ideas.md` by the 3 % gate.

Check: a `thinking` line in `rtok stats --since 30d` (text and JSON), a unit test on a fixture transcript, a dated row in `research.md` §2, and I-86 updated either way; ≤ 150 LOC.

Done: `measure::jsonl` collects `thinking`/`redacted_thinking` blocks per unique `message.id`; `measure::stats` sums them over sessions (text line + JSON `thinking`), share against uncached + cache_create + cache_read input. 2026-09-21, `rtok stats --since 30d`, 827 sessions: 28,072 blocks, 7,266,318 B ≈ 1,816,580 est. tokens = 0.0297 % of session input → I-86 moved to Rejected in `ideas.md`; row in `research.md` §2.
