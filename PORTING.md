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
| semver crate (dependency) | `libs/semver` (`:semver`) | ✅ own module | 0 → 3 |
| crates/rtok-store tests_archive (archive rows, retention, schema drift vs schema_snapshot.txt) | `ArchiveTest.kt` + fixtures | ✅ | 22 → 29 |
| crates/rtok-store embed | `Embed.kt` | ✅ | 16 → 17 |
| src/plugins/mod.rs registry, measure, proxy, render::plugins_table | `rtok/plugins/Registry.kt`, `Manifests.kt`, `Render.kt`, `ShellWord.kt` | ✅ | 3 → 4 |
| src/project.rs | `rtok/Project.kt` (+ test `Vfs`) | ✅ | 13 → 13 |
| src/plugins/docs | `rtok/plugins/docs/Docs.kt` | ✅ (fetch CLI pending zstd approval) | 12 → 13 |
| (new) libs/http, libs/http-ktor | `rtok.http` interface, Ktor adapter | ✅ | – → 4 |
| src/plugins/guard (mod, sed, skill), read/outline.rs markdown half, cmd/run.rs quoting | `rtok/plugins/guard/` (Guard, Sed, Skill), `plugins/read/Outline.kt`, `plugins/cmd/Run.kt` | ✅ (grep_symbol → graph) | 53 → 46 (+1 TypesTest) |
| src/plugins/cmd (all files but catfile.rs) | `rtok/plugins/cmd/` + rtok-sys `Proc.kt` | ✅ except catfile (tree-sitter + read cache) | 207 Kotlin tests (Rust 207; catfile 4, cat re-read delta 1, Windows-only cmd.exe run 1 not ported) |
| src/plugins/inject | `rtok/plugins/inject/` (Inject, Modes) | ✅ (hook-path halves → Phase 6) | 8 → 9 |
| src/plugins/memory (mod, scrub, admit, files, pack, observe, handoff, sync, export, import, extract), src/cli/memory.rs | `rtok/plugins/memory/` (Memory, Scrub, Admit, Files, Pack, Observe, Handoff, Sync, Portable, Extract), `cli/MemoryCmd.kt`; rtok-sys `ProgressRunner` stdin/timeout/quiet | ✅ (graph-range handoff test → Phase 10; `memory status`, `sync --stat`, MCP dispatch pending) | 127 → 126 |
| crates/rtok-agent-sdk backup/write subset | `rtok-agent-sdk/…/Backup.kt` (`Apply`, `backup`, `pruneBackups`, `staleBackups`, `write`, `writeAtomic`) | 🟡 rest is Phase 9 | via sync tests |
| crates/rtok-agent-sdk `PluginLink`, `LinkHealth`, `CopyFix`, `in_versioned_store`, owned copy | `rtok-agent-sdk/…/PluginLink.kt`; rtok-sys `EntryKind.kt` (`entryKind`, `readLink`, `symlink`, `copyFile`) | ✅ (unix; skill copy pending) | 14 → 14 |
| src/agents hook_events.rs, plugin.rs `HostPlugin`, mod.rs `plugin_src`, cline | `rtok/agents/` `HookEvents.kt`, `PluginSrc.kt`, `HostPlugin.kt`, `Cline.kt` | ✅ | 4+7 → 17 |
| src/agents/mod.rs shared host helpers (`shell_quote_bin`, `rtok_hook_bin`, `hook_resolver`, `unquote_bin`, `takes_hook`, `removed_report`, `with_kept`, `run_cli`, `manifest_names`, `offer_plugin`, `print_offer`) | `rtok/agents/HostHelpers.kt` | ✅ | 2 → 13 |
| src/agents/claude/mod.rs hook JSON walk (`HookForm`, `insert_ours[_as]`, `prune_ours`, `strip_ours[_as]`, `command[_for]`, `is_ours[_for]`) | `rtok/agents/` `ClaudeHooks.kt`, `HookInsert.kt`, `HookStrip.kt` | ✅ (claude host itself pending) | 5 → 7 |
| src/agents/mod.rs `d21_plugin_apply`; src/agents/qwen | `rtok/agents/` `D21Plugin.kt`, `Qwen.kt` | ✅ | 5 → 4 (1 reads the Rust plugin tree) |
| src/agents/commandcode | `rtok/agents/CommandCode.kt` | ✅ (`skill::sync` with skills) | 10 → 9 (payload test belongs to the hook dispatcher) |
| src/agents/copilot; src/agents/mcp.rs `Writer`/`run`/`sync`/`install`/`remove` | `rtok/agents/` `Copilot.kt`, `McpRun.kt` | ✅ (`skill::sync` with skills) | 7 → 6 (PreCompact note belongs to the hook dispatcher) |
| src/agents/codewhale; src/agents/mod.rs `load_toml` | `rtok/agents/` `Codewhale.kt`, `HostToml.kt` (on `:toml-aot`) | ✅ | 4 → 5 (support row lives with Hosts; +comment kept, broken config, edited table) |
| src/plugins/checkpoint.rs | `rtok/plugins/checkpoint/`, `rtok/plugins/memory/Private.kt`, `rtok-sys` Shlex/LineReader | ✅ (3 hook-dispatcher tests → Phase 6) | 27 → 27 |
| src/plugins/compress | `rtok/plugins/compress/` (Compress, MustKeep, Llm, PromptToml) + Compressor, Queue; HTTP via `libs/http` + `libs/http-ktor` | ✅ (proxy-side `submit` call → Phase 7) | 19 → 22 (+4 KtorHttpTest) |
| src/plugins/json_tree | `rtok/plugins/jsontree/JsonTree.kt`, `rtok-sys/Sha1.kt`, `Text.kt` jsonQuote | ✅ (3 MCP tests pending) | 15 → 12 |
| src/plugins/toon, src/proxy/anthropic.rs (ToolResults only) | `rtok/plugins/toon/Toon.kt`, `rtok/proxy/Anthropic.kt`, `Wire.kt` | ✅ (expand row pending) | 10 (+rstest folded) → 12 + 2 |
| src/plugins/archive (mod, pi), src/cli/archive.rs | `rtok/plugins/archive/Archive.kt`, `Pi.kt`, `cli/ArchiveCmd.kt` | ✅ (4 wire/gate tests pending) | 24 → 20 + 5 CLI + 3 e2e |
| src/plugin.rs (Runtime host), src/log.rs record | `rtok/plugin/Runtime.kt`, `rtok/Log.kt` | ✅ | 6 → 8 |
| toml crate (dependency) | `libs/toml` interface + `libs/toml-ktoml` (ktoml adapter) | ✅ | 0 → 12 |
| diesel/libsqlite3-sys (dependency) | `libs/db` interface + `libs/db-room` (androidx.sqlite bundled) | ✅ | 0 → 5 |
| crates/rtok-sys | `:rtok-sys` | ⬜ (module currently hosts `rtok.sys.Fs`, the fs-kit replacement) | |
| fs-kit (external crate) path helpers | `rtok-sys/nativeMain/Fs.kt` | 🟡 normalize/canon/ancestors/starts_with | via root tests |
| crates/rtok-mcp | `:rtok-mcp` | ⬜ | |
| src/plugins/wasm.rs (WASM plugin host) | `libs/wasm` interface, `libs/wasm-chasm` (Chasm 2.3.0 adapter, fuel), `rtok-wasm` `WasmPlugin.kt`, `rtok/plugins/WasmPlugins.kt` (registry wiring); docs/wasm-plugins.md | ✅ | 3 → 5 + 1 registry + 3 adapter |
| crates/rtok-wasm-demo-guest | – (built `.wasm` kept as test fixture) | ❌ wasm guest, not ported | |
| crates/rtok-webui | – | ❌ excluded (web) | |
| src/tokens.rs | `rtok/…/rtok/Tokens.kt` | ✅ | 4 → 4 |
| src/modes/ladder.rs | `rtok/…/rtok/modes/Ladder.kt` | ✅ | 3 → 3 |
| src/modes/compress.rs | `rtok/…/rtok/modes/Compress.kt` | ✅ | 16 → 16 |
| src/cli (32 files, 30 subcommands) | `rtok/…/rtok/cli` | 🟡 root command only (Clikt) | |
| src/plugins/read (mod, roots, search, cache, hook, outline, density) | `rtok/plugins/read/` `Read.kt` (ReadPlugin, readWith, resolve, cap), `Roots.kt`, `Search.kt`, `Cache.kt`, `Hook.kt`, `Outline.kt`, `Languages.kt`, `Density.kt` over `libs/treesitter` + `libs/treesitter-native` (34 grammars); `rtok/HostPaths.kt` (normalize_following, is_secret, same_spelling, is_linked_worktree) | ✅ (MCP dispatch → `rtok mcp`) | 101 → 98 + 5 walk in rtok-sys (+7 adapter) |
| src/expand.rs, src/cli/expand.rs | `rtok/Expand.kt`, `cli/ExpandCmd.kt` | ✅ | 27 → 27 |
| src/plugins/graph/walk.rs | `rtok/plugins/graph/Walk.kt` (`Matcher`) | ✅ (rest of graph ⬜) | 5 → 5 |
| src/plugins/graph/index.rs (+ worker pool) | `rtok/plugins/graph/Index.kt`, rtok-sys `Parallel.kt` | 🟡 rank refresh via `FileRanks` (rank.rs ⬜) | 15 → 14 (+2 pool); rank 3 + crate fixture pending |
| src/plugins/graph/resolve.rs, tally.rs | `rtok/plugins/graph/Resolve.kt`, `Tally.kt` | ✅ | 1 → 1, 4 → 4 |
| src/worktree/git.rs `list`, mod.rs `parse_porcelain` | `rtok/worktree/Git.kt` | 🟡 rest of worktree pending | via RootsTest |
| src/plugins (compress, json_tree, toon, read, cmd, inject, guard, memory, measure, docs, archive, checkpoint, graph, proxy, wasm) | `rtok.plugins` | ⬜ | |
| src/proxy | `rtok.proxy` (Ktor) | ⬜ | |
| src/mcp | `rtok.mcp` | ⬜ | |
| src/hooks | `rtok.hooks` | ⬜ | |
| src/agents (40+ hosts), src/doctor | `rtok.agents`, `rtok.doctor` | ⬜ | |
| src/otel, worktree, tasks, measure, model, report, bench, completions, project, store | matching packages | ⬜ | |
| src/tui | – | ❌ excluded (TUI) | |
| src/web, agents/junk_web.rs, `rtok web/tui/dashboard` | – | ❌ excluded (web) | |
| tests/*.rs (138 integration files) | `:rtok` commonTest | ⬜ | 0 / ~138 files |

Totals: 1098 unit + 26 CLI + 15 e2e unit (`macosArm64Test`, all modules) + 26 CLI (`cliTest`) + 14 e2e (`e2eTest`) tests passing on macosArm64 (see port-plan.md for the per-phase table).

| Rust | Kotlin | Status | Tests |
|---|---|---|---|
| src/names.rs | `rtok/commonMain/Names.kt` | ✅ | (covered by types tests) |
| src/hooks/types.rs | `rtok/commonMain/hooks/Types.kt` | ✅ | 18 → 18 (guard cache_key assertion deferred to Phase 5) |
| src/hooks/deadline.rs | `rtok/nativeMain/hooks/Deadline.kt` (Worker watchdog) | ✅ | 3 → 3 |
| src/hooks/resident.rs | `rtok/nativeMain/hooks/Resident.kt` (poll loop, no tokio) | ✅ | 1 → 2 |
| src/hooks/push.rs, mod.rs, src/cli/hook.rs, src/render.rs message frame | `rtok/hooks/Dispatch.kt`, `HostOutputs.kt`, `Push.kt`, `RenderMessages.kt`, `cli/HookCmd.kt`; rtok-sys `processAncestors`, `currentExe`, `spawnDetached`, `readFdBounded` | 🟡 dispatcher, all host shapers, push, resident wiring; worktree/otel/MCP steps via `HookLinks` no-ops | 55 → 26 |

## Test groups
unit `./gradlew macosArm64Test`, CLI `./gradlew cliTest`, e2e `./gradlew e2eTest`, wasm `./gradlew wasmTest` (docs/testing.md).

## Dependencies added 2026-10-26 (Ivan approved)
Ktor client (now 3.6.0) (`ktor-client-core`, `-darwin`, `-curl` on Linux, `-mock` tests only) and kotlinx-coroutines-core 1.10.2 (the version Ktor requires), in `libs/http` / `libs/http-ktor` only.
