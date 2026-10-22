Every Overview KPI and panel links to its page with the matching filter from T414.10 (failed calls → Calls filtered to failures, live sessions → Sessions filtered to live, plugins on → Plugins). Whole-card links with a visible focus ring.

Check: a story asserts each card's link target; e2e clicks one KPI and lands on the filtered page.

Result: `tableLink(page, filter)` in `web/src/tableSearch.ts` builds typed links from `TABLE_SPECS`; `Kpi` takes `to` and becomes a stretched link whose overlay carries the focus ring while the focusable chart stays above it (no nested interactive elements). The calls, live sessions and plugins on KPIs link to `/calls`, `/sessions?show=live` and `/plugins?show=on`, the failed count to `/calls?result=failed`; the calls legend entries and a live link in the sessions header use the same helper; panels keep their header links; input tok, output tok and cache hit have no matching page and stay plain. `Pages/Overview/Overview Links` story asserts all seven targets with axe; unit tests cover `tableLink` and its round trip; e2e `a KPI card on the Overview opens its page already filtered`. Web unit 201 passed, `just spa-stories` 129 passed, `just spa-e2e` 19 passed, `just dup js` clean. #832.

Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
