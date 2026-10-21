An export button on the Calls, Sessions and savings-by-plugin tables downloads the rows currently shown (after filters) as CSV or JSON, built in the browser from the snapshot. CSV cells that start with `=`, `+`, `-` or `@` are escaped against formula injection.

Check: unit tests for the CSV writer (quoting, escaping, empty table); a story asserts the button and its accessible name.

Result: `web/src/ui/tableExport.ts` builds the file from the same filtered rows, columns and sort that `DataTable` renders (`sortRows` reused; optional `Column.exportValue`, falling back to `sortValue`; value-less columns such as the share bar are left out). The RFC 4180 CSV writer quotes and doubles quotes, writes CRLF lines, and writes an empty table as its header row. Text cells starting with `=`, `+`, `-`, `@`, tab, CR, LF or a full-width variant are quoted behind an apostrophe per the OWASP CSV injection page (checked 2026-10-08); numbers are never escaped. `ui/ExportButtons.tsx` is a CSV/JSON button group ("Export <table> as CSV|JSON", disabled with no rows) in the `Panel` action slot of Calls, Sessions and Overview's savings by plugin. Web unit tests 223 passed, `just spa-stories` 130 passed (axe), `just spa-e2e` 18 passed; `just dup js` green. #830.

Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
