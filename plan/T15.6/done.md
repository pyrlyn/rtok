**T15.6 Doctor tab** · T15.0 · `src/web/model.rs`, `src/tui/{app,view}.rs`, `tests/surface_parity.rs`
Do (roadmap §TUI): Doctor tab — render what `rtok doctor` reports off the shared D23 model
(the snapshot carries the page), with no probe of its own.
Complexity: 1/5
Status: done 2026-09-10 · Model: GLM-5.3 (subagent; adopting the disconnected subagent-doctor-tab heap onto post-T15.3 HEAD)
Check result: green. Snapshot gains `doctor: Option<doctor::Report>` and `pages()` gains
`("doctor", "doctor")`; `Model::snapshot` is the one probe run (D27), fail-open to `None`.
The tab renders `Report::to_text()` verbatim — the same text `rtok doctor` prints.
Hermetic doctor paths in `tui::app::tests::config` so every `App::new` does not spawn this
machine's MCP servers. `doctor` moves from `surface_parity`'s EXEMPT to COMMAND_PAGES.
Tests: `doctor_page_rides_the_snapshot` (model Report == direct `doctor()`, wire key),
`doctor_tab_shows_what_rtok_doctor_reports` (TestBackend). `just check` exit 0 (fmt, clippy
`-D warnings`, workspace tests, build-min, jscpd 38 clones — no new clone in the task's files).
Deviations: 4 files (parity is the T15.12 move the EXEMPT comment promised), +84/−8 — under
≤200 LOC. The disconnected heap was based on a pre-T15.3 tree and would have stripped Overview;
this commit ports only the doctor page onto current HEAD.
