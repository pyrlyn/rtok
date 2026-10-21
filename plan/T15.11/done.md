**T15.11 the model covers every reading command** · T15.0 · `src/web/model.rs`, `src/measure/stats.rs`, `src/cli.rs`
Do: move the queries the reading commands own into the D23 model, one command at a time, and have
the command render what the model returns. `stats` is the hard one and goes first: it counts
transcript files while the model sums `usage` rows, so the two disagree about what a session is —
D27 says one of them is the model and the other is a renderer. `doctor`, `plugins`, `config show`,
`logs`, `demon status` follow; each is a page.
Check: `grep -r 'Store::open' src/` outside `src/web/model.rs` finds only writing commands and the
three surfaces; `rtok stats` output is unchanged for a fixture store.
Complexity: 4/5
Status: done 2026-09-09 · Model: GLM-5.3 (subagent)
Check result: green. stats, doctor, plugins, config show (+ `config get` and `set`'s value re-print,
which share its query), logs (+ export) and demon status/list now ask `src/web/model.rs`
(`stats_report` / `plugin_stats` / `cache_health`, `doctor::page -> Report`, the Plugins page via a
shared `render::plugins_table`, `config_entries` over the layered figment, `log_lines`, `demon::rows`);
no reading command opens the `Store` any more. The grep after the move hits only `plugin.rs`
`Runtime::open` (the hook/graph-index/otel runtime), `plugins/checkpoint.rs` (PreCompact restore on
the hook surface), `proxy/mod.rs` (the proxy surface) and store tests. Output-unchanged proof:
`tests/stats_model.rs` pins `rtok stats` table/`--json`/`--plugin cmd`/`--cache` byte-for-byte on a
fixture store (goldens recorded from the pre-move binary), and nine more command variants (doctor
plain and `--instructions`, plugins, config show/`--sources`/get, logs, logs export, demon status /
list / `status <svc>`) each diffed empty against the pre-move binary — only the inherent
`current_exe` path in doctor's self-listed MCP server differs between two binaries. The ws
`Snapshot` is unchanged, so T15.10's `surface_parity` still passes with `pages()` at
[overview, plugins]. Full `just check` green on the combined tree (with T24.1 + T15.10).
Deviations: the stats-vs-model definitional conflict is resolved structurally, not renumbered — the
model owns both definitions, one per page (Stats page: sessions = transcript files; Overview:
usage-row sums), with the store's authoritative session definition left to T25.1's Sessions page;
forcing one number would have changed output the Check pins without proving the old number wrong
(recorded in the `stats_report` doc comment and the commit body). 11 files, +663/−199 — the task
itself spans six commands. `rtok otel status` still reads through `Runtime::open`; it is not in the
task's command list, and T15.12's enumeration should decide it. graph_truth entries for
`plugin_json` (this task) and `read_settings` (T27.0, same round) were removed in the same landing —
annotated in the fixture header, per T8.19's rule that a must-appear list may not name nonexistent
sites.
