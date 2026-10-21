A time series of `Measurement` savings (est before − after per day), total and per plugin, added to the snapshot by the Rust model and drawn on Overview and Stats.

Check: `savings_trend_buckets_rows_by_day_and_plugin` (UTC and Europe/Kyiv day edges, window start, a future row left out, `expand` netting, empty days `null`); `savings.test.ts`; stories `Pages/Savings trend` (sample data, light, no rows) with axe; `ws.schema.json` blessed and `snapshot.gen.ts` regenerated.

Result: `Store::measurement_totals_since(since)` is the `measurement_totals` aggregate (shared row mapping) grouped by `(plugin, kind, ts)` over a window, because Diesel 2.3 cannot group a computed day and day edges move with the zone. `web::model::savings_trend` folds it per day and plugin through `fold_total`, the same fold the Plugins page totals use. `Overview.savings` holds 14 `SavingsDay`s (`day`, `rows`, `saved`, `plugins`) ending today in `[agents.usage] tz`, the Usage page's day edges. A day without rows has `saved: null` and no plugins: no row, no claim. Chart layer: a series value may be `null`, drawn as a gap, and the tooltip says "no data" instead of 0. `web/src/pages/SavingsTrend.tsx` draws stacked bars for the top three plugins plus "N other", or an empty state when no day has rows. `just check` 2707 passed, 8 skipped (earlier failures were disk pressure and one host-load test; all pass alone); `just spa-stories` 130 passed; `just spa-test` 186 passed; `just spa-e2e` 17 passed. #831.

Status: done 2026-10-08

Model: Claude Code / claude-opus-5-5
