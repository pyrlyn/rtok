Confirmed on `2c7c40b2d8af0673f085d71d8d3e1da0051084e5` with the actual [Panel](src/tui/panel.rs#L144) and Ratatui's test backend. The shown-result key handler passes `lines.len()` to `scroll_key`, which caps the offset at the number of logical lines minus one. [render](src/tui/panel.rs#L199) enables paragraph wrapping and applies that offset in rendered rows. A one-line result that wraps beyond the viewport therefore has a maximum offset of zero: even 100 PageDown events leave the same first rows visible and its tail inaccessible. A directly scrolled Paragraph renders the tail, and the unwrapped multi-line control scrolls normally. Compare/export result and error text share this panel.

Execution plan:

1. Add a small-viewport regression with one long result line and a distinctive tail. Send Down/PageDown and assert the tail becomes visible; retain the ordinary multi-line control.
2. Calculate or maintain the scroll range from wrapped rows at the current content width and viewport height. Share that geometry between rendering and navigation rather than capping against the logical input line count.
3. Clamp the offset when resizing or replacing the result; cover Unicode, blank lines, narrow terminals, short results, Up/PageUp and the compare/export result paths.
4. Run focused TUI panel and caller tests, then `just check`.

Check: every rendered result row is reachable using the existing navigation keys. The isolated probe imported the production panel with small surrounding UI shims and used a real Ratatui buffer; it did not execute an interactive terminal session or graph backend.

Result: The TUI panel remembers its width from the last draw and caps scrolling at the wrapped row count (`Paragraph::line_count`, ratatui feature `unstable-rendered-line-info`); render clamps a stale offset after a resize.

Check: `a_wrapped_line_scrolls_to_its_last_rows`, `unwrapped_lines_keep_their_scroll_range_and_a_resize_clamps_it`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
