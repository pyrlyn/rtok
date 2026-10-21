First slice of T358: scope is the T358.1 bullet under "Split when claiming" there; the spec text stays in T358.

Moved out of the first slice (to keep it near 300 LOC), all to T358.2 unless noted: `--by agent|model`; the saved tokens / saved estimate columns and the `rtok saved` summary line (they come from the `measurements` ledger and are only needed once `both` exists); display names (`Claude Code`; this slice prints the host id); the JSON `skipped` field; flipping the `[agents.usage] source` default from `rtok` to `logs` (the default is `rtok` until logs exist). The store read groups by session, model and timestamp, not by quarter hour: Diesel 2.3 cannot `GROUP BY` a computed `ts / N` (the gap `Store::usage_by_model` documents), so the grain is the request; revisit if a large store makes it slow.

Check: the items of T358's Check list that apply to `--source rtok` (fixture totals in text and JSON, unpriced warning, `--tz` and DST, match with `stats --price`, config rows); `just check`.

Execution:

1. `jiff` (already in the lock through env_logger) as a direct dependency for IANA zones with DST; row in `toolchain.md`.
2. `[agents.usage]` in `src/config/mod.rs` (schema from the types), `config/default.toml` and `docs/config.md` rows.
3. `Store::usage_slices` (Diesel, no raw SQL) in `src/store/mod.rs`; `src/agents/usage.rs` builds the report, reusing `row_cost` and `[stats.prices]` from `measure::stats` and the `render::table` helper.
4. CLI: `rtok agents usage` in `src/cli.rs` with the `[agents.usage]` flag overlay.
5. Gates: `tests/config_coverage.rs` (flag to key mapping), `tests/surface_parity.rs` (EXEMPT until T358.5), trycmd cases and regenerated goldens, README row.
6. Verify with the `agents::usage` unit tests (golden text, JSON field names, zone and DST cases), then `just check`.

Result (2026-10-03, Claude Code / sonnet-5): `rtok agents usage` now prints the T358 screen for what passed through rtok: header with the last day and zone, summary (tokens, estimated cost, sessions, daily rows), the unpriced warning, the per-agent table and monthly (or `--daily`) totals; `--json` carries the same rows with the four token legs, `--unpriced` lists the models without a price. Flags `--source`, `--host`, `--since`, `--until`, `--daily` / `--monthly`, `--tz` map to `[agents.usage]` (`source`, `hosts`, `since`, `until`, `period`, `tz`), with `default.toml` and `docs/config.md` rows. `Store::usage_slices` reads the `usage` rows through Diesel; `src/agents/usage.rs` buckets them by day and month in `--tz` with `jiff` (DST-aware, new direct dependency, already in the lock) and prices them through `measure::stats::row_cost` and `[stats.prices]`, with provider prefix and date suffix stripped before the lookup. Checked: seven `agents::usage` unit tests (golden screen text, JSON field names, a request across UTC midnight, month edges before and after the Kyiv DST change, host and window filters, total equal to the `stats --price` arithmetic), trycmd cases for the empty store and `--help`, and the `config_coverage` and `surface_parity` gates (`agents usage` is `EXEMPT` until T358.5 adds its page). The cut-out parts are recorded in the T358.1 card above and landed in the T358.2 card.

Status: done 2026-10-03
Model: Claude Code / claude-sonnet-5
