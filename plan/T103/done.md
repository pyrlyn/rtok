No test calls `Store::memory_recall_totals` (`src/store/mod.rs`) or `call_io_archives`. Add unit tests on an in-memory store: empty store, one row, many sessions, rows outside the window.

Check: both functions covered by `src/store` unit tests; `just test` green.

Do (2026-09-21): two unit tests in `src/store/mod.rs` on `Store::open_in_memory`. `memory_recall_totals_sums_recalls_in_the_window_only`: empty store `(0, 0, 0)`; one row; three sessions summed while a `memory`/`save` row and a `read`/`recall` row stay out; one row backdated a day falls outside a one-hour window, and a window starting in the future is empty. `call_io_archives_names_only_spilled_bodies`: no `call_io` row → `(None, None)`; inline bodies → `(None, None)`; an over-cap request → its sha, which names a file in the archive dir, response `None`; both over cap → both ids; over cap with no archive dir (the hook path) → `(None, None)`.

Check result (2026-09-21): both tests pass; `just check` green with the branch.
