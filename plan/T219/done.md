Found 2026-09-22 in the store/accounting pass: every `p95` cell in the tool/bash/mcp/skill tables is set to `row.max` (`src/measure/stats.rs:1054-1058`, `fold_skills` :909 — "p95 approximated as max until we store samples"), so the column labelled `p95` systematically overstates the percentile. Only `src/web/model.rs:811-818` computes a real nearest-rank percentile. An operator comparing `rtok stats` p95 against `research.md` baselines or provider numbers gets maxima.

Plan: keep a bounded reservoir of per-row sizes in `add()` and compute true nearest-rank p95 in `finish_rows`, or rename the column to `max` if the approximation is deliberate (creator's call — the rename keeps the number honest either way).

Check: unit test with 100 samples of 1 and one of 1000 asserting `row.p95 != row.max` (fails today); `ctt_and_tool_totals_on_mini_session` snapshot extended with a p95 fixture; `just test` green.

Do (2026-09-23): `add` keeps a bounded per-row sample sidecar (2 048 sizes, deterministic halving past the cap, no RNG) and `finish_rows` / `finish_skills` compute nearest-rank p95 from it; `collect` walks session files in path order so the sidecar is reproducible. Check: `p95_is_nearest_rank_not_max`, `p95_survives_the_cap_without_rng` and the extended `ctt_and_tool_totals_on_mini_session` fixture (21 Reads: p95 1, max 1 000) green.
