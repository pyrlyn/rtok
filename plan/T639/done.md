Confirmed on `ea999781d7a48733e3a5c92e711cfad291c13536` in [render_calls](src/tui/view.rs#L609) and [calls_table](src/tui/view.rs#L654). The cursor advances through all snapshot rows, but the renderer uses a stateless `Table` with no offset; selection only changes a row's style. A Ratatui 0.30.2 TestBackend probe of the actual `calls_table`, with 15 rows in a 100-by-5 area, renders row 0 and highlights it when selected. Selecting row 10 still renders row 0, hides row 10 and leaves no highlighted row on screen. T15.5 added the Calls cursor and details; T60.10 fixed viewport tracking for Sessions, not Calls. T619 is the independent wrapped-result panel issue.

Execution plan:

1. Add a Calls rendering regression navigating beyond a short viewport and back, asserting that the selected row remains visible and highlighted.
2. Use Ratatui table state or the existing derived-offset convention to scroll the Calls list to its cursor, accounting for its header and border.
3. Cover terminal resize, snapshot shrink, empty rows and opening the detail/expand panes, which reduce the list height. Keep selection and details on the same call.
4. Run TUI state/rendering tests and `just check`.

Check: keyboard navigation never leaves the Calls cursor outside its visible list. The executed probe used the production table function and real TestBackend; row data and formatting/theme dependencies were small fixtures, and full App navigation was source-traced.

Result: `calls_table` windows its rows with the same derived offset the Sessions page uses, from the list area height.

Check: `calls_tab_scrolls_to_keep_the_cursor_visible`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
