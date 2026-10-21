Creator's bug report: the TUI hangs while loading and when switching tabs. Cause: `model::snapshot` (store, doctor probe, transcript parse — seconds on a busy machine) ran on the key loop — before the first frame, and on every tick, `r` and plugin toggle — so no key was read until it returned.

Do: `App::background` gives the running TUI a `Worker` thread that turns a config into a snapshot (queued requests collapse into the newest; a thread that will not start or dies falls back to inline reads, D1). The screen opens at once over `Snapshot::default()`; the loop waits at most 100 ms for a key, then `App::poll` lands finished reads; a timer tick is skipped while a read runs; the footer says `loading…`. `App::new` still reads inline for unit tests.

Check: `tui::app::tests::background_app_switches_tabs_while_the_model_loads` — tabs switch mid-read, the worker's snapshot lands, a tick mid-read is skipped; all `tui::` tests green; `just check` green.

Status: done September 21, 2026

Creator's bug report: the TUI hangs while loading and when switching tabs. Cause: `model::snapshot` (store, doctor probe, transcript parse — seconds on a busy machine) ran on the key loop — before the first frame, and on every tick, `r` and plugin toggle — so no key was read until it returned.

Do: `App::background` gives the running TUI a `Worker` thread that turns a config into a snapshot (queued requests collapse into the newest; a thread that will not start or dies falls back to inline reads, D1). The screen opens at once over `Snapshot::default()`; the loop waits at most 100 ms for a key, then `App::poll` lands finished reads; a timer tick is skipped while a read runs; the footer says `loading…`. `App::new` still reads inline for unit tests.

Check: `tui::app::tests::background_app_switches_tabs_while_the_model_loads` — tabs switch mid-read, the worker's snapshot lands, a tick mid-read is skipped; all `tui::` tests green; `just check` green.
