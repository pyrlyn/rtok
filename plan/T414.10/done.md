Filters, search text and sort of the Calls, Sessions, Plugins and Logs tables live in the route's search params (TanStack Router `validateSearch`), so a link restores the view and back/forward step through it. Blocker of T414.11.

Check: unit tests for search-param parsing (bad values fall back to defaults), page tests for URL-driven rows and back/forward, a DataTable story (axe), and an e2e that opens a filtered and sorted Calls link, reloads and steps back.

Result: one shared module (`web/src/tableSearch.ts`) holds a spec per page, the parse (untrusted values fall back to defaults, text capped at 200 chars) and the `useTableSearch` hook. Params: `q`, `sort` (`col` / `-col`), `surface` and `result` on Calls, `show` on Sessions and Plugins, `level` on Logs; defaults never appear in the URL, and the palette's `?id=` survives every change. `DataTable` gained opt-in sortable headers (`sortValue`, `aria-sort`, exported `sortRows`); Logs got an "oldest first" chip (`sort=-line`). `just check` 2706 passed, 8 skipped; unit 197 passed; `just spa-stories` 128 passed (axe); `just spa-e2e` 18 passed. #826.

Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
