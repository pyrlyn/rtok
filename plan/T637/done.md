Confirmed on `ea999781d7a48733e3a5c92e711cfad291c13536` in [App::key](src/tui/app.rs#L715). The global `r`/`?` branches run before the Config and Calls filter handlers. An isolated compiled probe using the verbatim routing prefix and [config_key](src/tui/app.rs#L483) typed `proxy?` into an active Config filter and obtained `poxy`, one refresh and an opened help overlay. Calling the filter handler directly preserves `proxy?`. The Calls filter is reached after the same global branches (source-traced). T60.8 establishes global help/refresh outside text entry; its tests do not exercise active filters. The existing compare/export typing exceptions already demonstrate the needed precedence. This is separate from T619's wrapped-row scrolling.

Execution plan:

1. Add App-level regressions typing `proxy?` and ordinary text into Config and Calls filters; assert exact text and no refresh/help side effects.
2. Dispatch active text-entry handlers before ordinary global shortcuts, keeping Ctrl+C and the documented finish/cancel keys deliberate. Reuse the compare/export routing convention.
3. Keep `r` and `?` functional outside text entry and cover closing a filter, reopening it, Backspace and switching pages.
4. Run TUI state/rendering tests and `just check`.

Check: every printable filter character reaches its input without firing an unrelated command. The executed probe compiled the actual routing prefix and Config handler with unrelated page/model methods stubbed; a full interactive terminal and Calls input were not run.

Result: An open Calls or Config filter takes keys before the global `?` and `r`; Ctrl+C still quits.

Check: `filter_text_is_not_stolen_by_global_shortcuts`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
