**T24.1 every log line goes through the funnel** · T24.0 · `src/plugin.rs`, `src/proxy/mod.rs`
Do: `Ctx::log` writes the file line *and* the `logs` row (`to_db` false skips the row, and the file
is then the only sink — the reason the key exists). The proxy's private `log(store, …)` helper
routes through the same funnel instead of inserting on its own; there is one writer, not two.
Fail open stays fail open: an unwritable log directory never turns into an error a plugin sees.
Check: a plugin call leaves one line in the file and one row in the table; with `to_db = false`,
one line and no row; a read-only log directory changes nothing about the call's result.
Status: done 2026-09-09 · Model: GLM-5.3-Flash (zai-coding-plan)
Check result: green. All three writers are one: log::record is the funnel, Runtime::log and the proxy's
log helper (including finish's log_err) both call it, and neither inserts a logs row on its own. A
plugin call leaves exactly one line in the file and exactly one row in the table
(a_funnel_call_is_one_file_line_and_one_row, a_plugin_call_is_one_file_line_and_one_row); with
to_db = false one line lands and logs_after stays empty (to_db_false_leaves_the_file_as_the_only_sink);
a read-only log directory changes nothing about the call — the row still lands, the file write fails
silently (a_read_only_log_directory_changes_nothing_about_the_call, root-guarded). The proxy's rows
keep the shape insert_log gave them (proxy_log_rows_keep_their_shape_through_the_funnel). cargo test
--lib 187 passed; proxy/logs/otel/demon integration suites 29 passed; clippy -D warnings and fmt
clean, verified in a detached worktree at 5975877. Note: [log] level (default info) now gates both
sinks, so a line below level no longer reaches the logs table either — the funnel's one-decision
semantics, not a regression. Deviation: the [core] log_file/log_level/log_to_db → [log] key migration
T24.0's preamble deferred to "T24.1's commit" is not here — it needs src/config/mod.rs, a fourth
file; after this commit core.log_file has no production reader, so it is a pure config commit of its own.
