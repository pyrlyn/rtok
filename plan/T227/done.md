Found 2026-09-23 in the D27 audit: `rtok stats` — transcript report, per-plugin CTT, cache health, `--price` per-model USD, baseline compare — is CLI-only; `tests/surface_parity.rs:384` exempts it as "no snapshot page yet". The data already flows through `web::model::stats_report`, `cache_health`, `plugin_stats` and `store::usage_by_model` (`src/store/mod.rs:1536`, read only by `measure/stats.rs:655`); the Overview page carries `usage_by_api` only, so the P15 gate ("Overview numbers match `rtok stats --json`") covers a fraction of the command.

Plan: `model::pages()` gains `("stats", "stats")`; the snapshot carries the stats report rows, cache health, per-model usage and cost; one TUI tab and one Slint page render them; `stats` moves from `EXEMPT` to `COMMAND_PAGES`. Reuse the accessors — no second aggregation (T207 owns the totals).

Check: `stats_page_exists_on_both_surfaces`; on the fixture store every number on the page equals `rtok stats --json` / `--price`; `just check` green.

Do (2026-09-24): `model::pages()` gains `("stats", "stats")`; `Snapshot.stats` carries `rtok stats --price`'s table plus the `--cache` table as text, built in `stats_skills` from the scan the Skills page already runs (TTL-cached, no second aggregation; prices are a local table, so the page always shows cost). The TUI renders it in `view::stats`; the Slint web UI adds a `stats` page (`PAGE_IDS`, `stats-text`, icon). `stats` moves from `EXEMPT` to `COMMAND_PAGES`. Tests: `stats_page_exists_on_both_surfaces`, `stats_page_matches_price_and_cache_on_the_fixture_store` (every line of `rtok stats --price` / `--cache` on the fixture store is on the page), `missing_stats_is_a_failed_tick_not_empty`. `just check`, `just webui-check` and the `rtok-webui` tests green.

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
