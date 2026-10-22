Confirmed on `2c7c40b2d8af0673f085d71d8d3e1da0051084e5` in [Session::did_open](src/plugins/graph/lsp.rs#L511). The first query reads a file and sends `textDocument/didOpen` with version 1. Subsequent calls return immediately if the URI is already in `opened`, before reading the file again. [with_session](src/plugins/graph/lsp.rs#L544) reuses the same live server for the workspace; the module sends no document-change or close notification. Editing a file between queries therefore leaves the peer with the original open-document text.

The isolated probe opened a temporary file containing `old_symbol`, changed it to `new_symbol`, and called the actual `did_open` implementation again. A synthetic protocol peer reported only one notification, still carrying `old_symbol`. This affects repeated queries in a resident MCP session; restarting a server happens to refresh the initial snapshot. T376's lifecycle/fallback work does not cover edits to an already opened document.

Execution plan:

1. Add a fake-peer test that opens, edits and re-queries one URI in the same session; assert the peer receives the new content before the query. Keep an unchanged-file control.
2. Track each opened document's content/version and send the appropriate full-content change or close/reopen sequence when it changes, respecting the negotiated synchronization capability. Do not mark a document as synchronized before its notification succeeds.
3. Cover consecutive edits, Unicode, deletion/read failure and session replacement; ensure query results and source ranges cannot silently refer to the old snapshot.
4. Run focused document synchronization and repeated graph-query tests, then `just check`.

Claimed design (single PR, well under 500 code lines): `opened` becomes a map of URI to the last text sent and its version. `initialize`'s `textDocumentSync` kind is kept on the session. `did_open` re-reads the file: unchanged is a no-op; changed sends a full-content `didChange` (version + 1) when the server syncs, else `didClose` + `didOpen`; a file that can no longer be read is closed and forgotten. State is recorded only after the notification is queued. `with_session` refreshes every already-open document before each call, so `workspace/symbol` and positions never come from an old snapshot. Tests use a recording `sh` peer (`cat` into a file), first run against the old `did_open` to prove they fail.

Check: a second query after a saved edit uses the current document text without restarting the MCP process. Executed evidence compiled the production document-open/framing methods against a synthetic peer; actual language-server analysis and full MCP integration were not run.

Result: `Session::did_open` in `src/plugins/graph/lsp.rs` keeps each open document's last text and version and re-reads the file on every call: unchanged is a no-op, changed sends a full-content `didChange` (version + 1) when `initialize` advertised change sync (`sync_kind`), otherwise `didClose` plus `didOpen`; a file that is gone or unreadable is closed and forgotten. A document is recorded only after its notification is queued. `with_session` calls `refresh_open` before every query, so documents opened by an earlier query are re-synced too and `workspace/symbol` positions never come from an old snapshot; a replaced session starts with no open documents. A recording `sh` peer covers edit, close-and-reopen, unchanged control, consecutive Unicode edits, deletion and re-creation, `refresh_open` and a failed notification; seven failed against the old `did_open` and pass now; `just check`.
Status: done 2026-10-12

Model: Claude Code / claude-sonnet-5-5
