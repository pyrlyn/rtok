**T45.1 OTel flush survives a traces error** · P0, 2/5 · `src/otel/export.rs`, `tests/otel.rs`
Do: `flush_into` no longer aborts the whole flush on a non-404 traces POST error. Each stream's `post` result is matched on its own: the error is kept in `rep.error` (joined when several streams fail), the traces mark stays, and logs and metrics still post and advance in the same round.
Check: `tests/otel.rs::traces_500_still_posts_logs_and_metrics` beside `a_traces_404_still_posts_logs_and_metrics`.
Status: done 2026-09-17 · Model: OpenCode / Muse Spark 1.3 (code); closed by Claude Code / Fable 5.1
Evidence: the code landed inside `a423aca`; `just check` exit 0 after T47.4 (716 passed), which runs `tests/otel.rs`.
Deviation: committed by another agent's snapshot commit (`a423aca`), not as its own `T45.1` commit; this commit only moves the task to done.md.
