Found 2026-09-22 in the store/accounting pass: `archive_in_session`'s per-lookup subquery filters `measurements` by `(session, ts)` (`src/store/mod.rs:583-597`) — no index covers it (only `measurements_plugin(plugin, ts)` exists), `usage_ctt` (:1470-1495) scans the whole `usage` table per dashboard tick, and `purge_calls_older_than` (:1715-1802) deliberately keeps `usage`/`measurements`/`read_cache` forever. `plugin::identical_result` calls `archive_in_session` per tool result on PostToolUse, so hook latency grows linearly with total history.

Plan: one migration `CREATE INDEX measurements_session_ts ON measurements (session, ts)` (also serving `last_measurement_ref`'s ordering family); if the dashboard scan still shows up in `doctor` latency, follow with the grouped aggregate from T207.

Check: `EXPLAIN QUERY PLAN` for `SELECT COUNT(*) FROM measurements WHERE session = ? AND ts > ?` reports `USING INDEX measurements_session_ts` (not `SCAN`); a latency fixture with 100 k measurement rows keeps `archive_in_session` under budget; `just test` green.

Result: Migration 0023 adds `measurements_session_ts (session, ts)`; `archive_in_session`'s correlated subquery now plans as `SEARCH measurements USING COVERING INDEX measurements_session_ts` instead of a scan. Tests: `archive_in_session_query_plan_uses_the_session_ts_index` (EXPLAIN QUERY PLAN) and `archive_in_session_stays_fast_with_100k_measurements` (< 200 ms).

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
