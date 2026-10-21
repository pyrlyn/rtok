**T24.4 the demon's own logs are bounded too** · T24.0 · `src/demon.rs`
Do: today `supervise` hands the child a raw appending fd, so `<service>.log` grows without limit and
rtok cannot rotate a file the child holds open. Pipe the child's stdout and stderr instead and let
the supervisor write them through the T24.0 sink, which is what makes rotation possible at all.
Check: a service that writes more than `max_bytes` ends with rotated `<service>.log.1`; `demon
status` still names the live file; the restart and backoff tests are unchanged.
Status: done 2026-09-09 · Model: Opus 5 (subagent)
Check result: green. `supervise` now spawns the child with piped stdout/stderr; two reader threads
(`pump`) only forward `(level, line)` over an `mpsc` channel — stdout as `info`, stderr as `warn` —
and the existing poll loop is the sole writer, calling `drain` through `log::append` against a
cloned `Config` whose `log.path` is the service's file. One writer means two streams on one file are
never a rotate/append race, and a child that never closes its pipe cannot wedge the loop. Both
readers are joined and the channel drained once more before a restart and on the stop path, so no
buffered line is lost. `cargo test --lib demon` 3 passed, `cargo test --test demon` 3 passed
(restart, backoff, status and stop unchanged); clippy and fmt clean on the file.
Deviations: one. The Check reads as an end-to-end run through a real supervised service, but no
built-in service emits a dial-in number of lines and the plan asks for none, so the new test
`a_service_that_writes_past_max_bytes_gets_a_rotated_log` drives `pump`/`drain` directly with a
`Cursor` of 50 lines against `max_bytes = 200` — the same path `supervise` now uses — and asserts
`mcp.log` and `mcp.log.1` both exist with the live file bounded.
