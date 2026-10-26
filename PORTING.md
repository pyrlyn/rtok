# PORTING status — rtok (Rust) → rtok-kotlin

Status legend: ✅ ported + tests green · 🟡 partial · ⬜ not started · ❌ excluded / not portable.
Plan and per-phase checklist: [port-plan.md](port-plan.md).

| Rust source | Kotlin location | Status | Tests (Rust → Kotlin) |
|---|---|---|---|
| crates/rtok-plugin-sdk (lib, host, wire, task_prefix, testing) | `:rtok-plugin-sdk` `Lib.kt`, `Host.kt`, `Wire.kt`, `Testing.kt` | ✅ | 6 → 9 (+terse doc example, wire write-back, task prefix) |
| crates/rtok-config `estimator.rs` | `:rtok-config` `rtok.config.Estimator` | ✅ | – |
| crates/rtok-config `root.rs` | `rtok-config/nativeMain/Root.kt` | ✅ | 7 → 7 |
| crates/rtok-config `since.rs` | `rtok-config/commonMain/Since.kt` | ✅ | 0 → 5 (new) |
| crates/rtok-config `lib.rs` (+ toml parser) | `Config.kt`, `Sections.kt`, `Toml.kt` | ✅ | 33 → 33 |
| crates/rtok-config `layers.rs` | `Layers.kt` (figment → `Fig`) | ✅ | 18 (+2 rstest cases) → 20 |
| crates/rtok-config `validate.rs` | `Validate.kt` (toml_edit → line editor) | ✅ | 26 → 27 |
| crates/rtok-sys `change_preview` | `rtok-sys/commonMain/ChangePreview.kt` | ✅ | → 4 |
| crates/rtok-log | `rtok-log/nativeMain/Log.kt` | ✅ | 12 → 12 |
| crates/rtok-hook | `rtok-hook` (common `Hook.kt`, native `Connect.kt`) | ✅ (unix only) | 5 → 5 (+1 new) |
| crates/rtok-store handle/lib/migrations | `rtok-store/nativeMain` `Db.kt`, `Store.kt`, `Migrations.kt` (+ generated `Migrations.gen.kt`) | ✅ | 2 → 10 (+Db 2) |
| crates/rtok-store sanitize, hook_fields (pure) | `Sanitize.kt`, `SerdeJson.kt`, `HookFields.kt` | ✅ | 11 → 11, 7 → 7 |
| crates/rtok-store retention, cache, kv | `Retention.kt`, `Cache.kt`, `Kv.kt` | ✅ | tests_schema 7 of 9 → 5 (+archive purge) |
| crates/rtok-store call_log, measure_rows, session_rows, archive_rows, note_store, observations, archive_summaries, models | `CallLog.kt`, `MeasureRows.kt`, `SessionRows.kt`, `ArchiveRows.kt`, `NoteStore.kt`, `Observations.kt`, `Models.kt`, `Rows.kt` | ✅ | tests_store 31 → 31 |
| crates/rtok-store otel, task_id, task_claims, task_counters | `Otel.kt`, `TaskId.kt`, `Tasks.kt` | ✅ | 8 → 8, 6 → 7 |
| crates/rtok-store agents, worktree_claims, worktree_pending, note_files, host_usage | `Agents.kt`, `WorktreeClaims.kt`, `WorktreePending.kt`, `NoteFiles.kt`, `HostUsage.kt`, `SqlExt.kt` | ✅ | 14 → 14, 8 → 9, 4 → 4, 0 → 1 |
| crates/rtok-store projects, symbols (+ symbol-root retention) | `Projects.kt`, `Symbols.kt` | ✅ | 7 → 7, 10 → 10, tests_schema 9 → 7 (+archive purge) |
| crates/rtok-store project_links | `ProjectLinks.kt` | ✅ | 5 → 5 |
| (adapters) SQLite driver, Clikt | `Db.kt` + `driver/Sqlite3Db.kt`; `cli/Mod.kt` + `cli/clikt/CliktAdapter.kt` | ✅ | new: 1 + 1 |
| crates/rtok-store symbol_graph, graph_events | `SymbolGraph.kt`, `GraphEvents.kt` (+ `chunkedIn` in `SqlExt.kt`) | ✅ | 3 → 3, 3 → 4 |
| crates/rtok-store docs | `Docs.kt` | ✅ | 0 → 2 (new) |
| crates/rtok-store messages | `Messages.kt` | ✅ | 7 → 8 |
| humantime crate (dependency) | `libs/humantime` (`:humantime`) | ✅ own module | 0 → 3 |
| crates/rtok-store tests_archive (archive rows, retention, schema drift vs schema_snapshot.txt) | `ArchiveTest.kt` + fixtures | ✅ | 22 → 29 |
| crates/rtok-store embed | `:rtok-store` | ⬜ | |
| crates/rtok-sys | `:rtok-sys` | ⬜ (module currently hosts `rtok.sys.Fs`, the fs-kit replacement) | |
| fs-kit (external crate) path helpers | `rtok-sys/nativeMain/Fs.kt` | 🟡 normalize/canon/ancestors/starts_with | via root tests |
| crates/rtok-mcp | `:rtok-mcp` | ⬜ | |
| crates/rtok-agent-sdk | `:rtok-agent-sdk` | ⬜ | |
| crates/rtok-wasm-demo-guest | – | ❌ wasm guest, not needed | |
| crates/rtok-webui | – | ❌ excluded (web) | |
| src/tokens.rs | `rtok/…/rtok/Tokens.kt` | ✅ | 4 → 4 |
| src/modes/ladder.rs | `rtok/…/rtok/modes/Ladder.kt` | ✅ | 3 → 3 |
| src/modes/compress.rs | `rtok/…/rtok/modes/Compress.kt` | ✅ | 16 → 16 |
| src/cli (32 files, 30 subcommands) | `rtok/…/rtok/cli` | 🟡 root command only (Clikt) | |
| src/plugins (compress, json_tree, toon, read, cmd, inject, guard, memory, measure, docs, archive, checkpoint, graph, proxy, wasm) | `rtok.plugins` | ⬜ | |
| src/proxy | `rtok.proxy` (Ktor) | ⬜ | |
| src/mcp | `rtok.mcp` | ⬜ | |
| src/hooks | `rtok.hooks` | ⬜ | |
| src/agents (40+ hosts), src/doctor | `rtok.agents`, `rtok.doctor` | ⬜ | |
| src/otel, worktree, tasks, measure, model, report, bench, completions, project, store | matching packages | ⬜ | |
| src/tui | – | ❌ excluded (TUI) | |
| src/web, agents/junk_web.rs, `rtok web/tui/dashboard` | – | ❌ excluded (web) | |
| tests/*.rs (138 integration files) | `:rtok` commonTest | ⬜ | 0 / ~138 files |

Totals: 358 Kotlin tests passing on macosArm64 (see port-plan.md for the per-phase table).

| Rust | Kotlin | Status | Tests |
|---|---|---|---|
| src/names.rs | `rtok/commonMain/Names.kt` | ✅ | (covered by types tests) |
| src/hooks/types.rs | `rtok/commonMain/hooks/Types.kt` | ✅ | 18 → 18 (guard cache_key assertion deferred to Phase 5) |
| src/hooks/deadline.rs | `rtok/nativeMain/hooks/Deadline.kt` (Worker watchdog) | ✅ | 3 → 3 |
| src/hooks/resident.rs | `rtok/nativeMain/hooks/Resident.kt` (poll loop, no tokio) | ✅ | 1 → 2 |
| src/hooks/push.rs, mod.rs | — | ⬜ | 0 / 55 |
