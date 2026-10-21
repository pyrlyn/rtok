---
status: todo
priority: P3
complexity: 2
readiness: 90%
agent:
---

# T436.4. Spinner on `agents install/update` through T276's `ProgressRunner`

Split from T436.2 (2026-10-08): T436.2 shipped the remaining waits and the operation icons on the `agents install/update/remove` header, but the card's install/update spinner rests on T276's `ProgressRunner`, which does not exist yet; the existing `with_loader("updating host")` stays until then. Depends on T276.

Check: `agents install` and `agents update` show one spinner per host on a TTY and nothing on a pipe (non-TTY test); trycmd snapshots unchanged; the creator's manual run of `rtok agents install` in a terminal.

Execution plan (2026-10-12): 1. replace `with_loader("updating host")` in the `agents install/update` path with a per-host spinner through `rtok_sys::proc` progress (T276.1/T276.2); 2. TTY only, nothing on a pipe; 3. non-TTY test, trycmd snapshots unchanged; the manual terminal run stays for the creator.

Progress (2026-10-12): `agents install/update` draw one `ProgressRunner::batch` bar stepped once per host (`src/agents/mod.rs`, `host_bar`), off a TTY and while the plugin question may be asked; `apply_hosts` no longer uses `with_loader`. Tests: `agents::tests::apply_all_steps_the_bar_once_per_host` (fail-first), `tests/agents_update.rs::install_and_update_draw_no_bar_on_a_pipe`; trycmd unchanged. A TTY run with `--yes` or `--dry-run` now shows the bar; the restart wait in `restart.rs` has no indicator until T276.3. Left: the creator's manual run in a terminal (bar per host, cleared before the result lines; no bar over the plugin question without `--yes`).
