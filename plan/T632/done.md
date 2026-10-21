Source-confirmed on `ea999781d7a48733e3a5c92e711cfad291c13536` in [pagination](src/report/pdf.rs#L77) and [bar rendering](src/report/pdf.rs#L243). A chart is budgeted as `pairs.len() + 2` lines at `LEAD = 4.6` mm, but each drawn bar advances 5.2 mm. The chunker accepts 56 bars as a 58-line page. Starting at TOP 282 mm, after the chart title the last bar baseline is `282 - 6 - 55 * 5.2 = -10` mm, below the page itself and well below the 15 mm bottom margin. The existing oversized-chart guard and T167's orphan-heading fix do not account for the different physical bar spacing.

Execution plan:

1. Add layout regressions at the maximum chart size, with and without a preceding heading/paragraph. Inspect the emitted positions and require every row to stay within the content area.
2. Express block costs and splitting in the same physical units used for rendering, including chart title and trailing spacing. Derive the maximum rows per chart from available height.
3. Preserve chart continuation, heading placement and progress guarantees for oversized and empty charts; check mixed charts and paragraphs.
4. Render representative reports for visual inspection, run PDF/report parity tests and `just check`.

Check: no chart row crosses the bottom margin. The executed probe checked the current source constants and arithmetic; an actual PDF renderer/viewer was not run in this audit.

Result: PDF report chart rows are budgeted at their drawn heights in `src/report/pdf.rs`, so they stay above the bottom margin.

Check: `chart_rows_stay_above_the_bottom_margin`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
