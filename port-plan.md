# rtok → Kotlin/Native port plan

Source: `apps/rtok` (Rust, ~200k lines incl. tests; `src/` 166k, crates 31k). Target: this repo.
Goal: behavior- and structure-preserving port; CLI flags, output formats, config (TOML) and
on-disk formats (SQLite ledger `rtok.db`, payload files) stay interchangeable with Rust rtok.
**Excluded:** TUI (`src/tui`, ratatui/crossterm UI, `rtok tui`) and web UI (`src/web`,
`crates/rtok-webui`, `rtok web`, `rtok dashboard`, `agents/junk_web.rs`).

## Standing rules (Ivan)
- Prefer popular, actively maintained libraries; own code only when none fits, and then as a separate module under `libs/`, not inside port code.
- No new dependency without Ivan's approval (pending: ktoml for TOML).
- Decompose: no copy-paste; shared helpers are extracted (e.g. `SqlExt.kt`).
- Every third-party library sits behind our own interface (adapter package), so it can be replaced alone. Not applied to Kotlin's own libraries (stdlib, kotlinx.*, JetBrains libs such as Ktor). Adapters so far: SQLite → `Db`/`Row`/`DbDriver` (`rtok-store/.../driver/Sqlite3Db.kt`); Clikt → `CliParser`/`CommandSpec` (`rtok/.../cli/clikt/CliktAdapter.kt`).
- After every finished item: tick port-plan.md + PORTING.md, update test counts, commit with the code. Local commits only.

## Structure mirror
| Rust crate / dir | Gradle module | Kotlin package |
|---|---|---|
| crates/rtok-plugin-sdk | `:rtok-plugin-sdk` | `rtok.plugin.sdk` |
| crates/rtok-config | `:rtok-config` | `rtok.config` |
| crates/rtok-log | `:rtok-log` | `rtok.log` |
| crates/rtok-hook | `:rtok-hook` | `rtok.hook` |
| crates/rtok-store | `:rtok-store` | `rtok.store` |
| (Rust dep `humantime`) | `:humantime` (`libs/humantime`) | `humantime` — own code, no maintained K/N library fits |
| crates/rtok-sys | `:rtok-sys` | `rtok.sys` (cinterop/posix) |
| crates/rtok-mcp | `:rtok-mcp` | `rtok.mcp` |
| crates/rtok-agent-sdk | `:rtok-agent-sdk` | `rtok.agent.sdk` |
| src/ (binary) | `:rtok` | `rtok.<module>` one package per `src/<dir>` |
| crates/rtok-wasm-demo-guest | — | excluded (wasm guest, no Kotlin/Native equivalent needed) |

## Library mapping
clap→Clikt; serde/serde_json→kotlinx.serialization; toml/toml_edit/figment→ktoml (+ own layering);
tokio→kotlinx.coroutines; axum/reqwest/rmcp→Ktor server CIO + client (Curl/Darwin engines);
diesel sqlite→SQLDelight native driver or sqlx4k (schema copied verbatim from `schema_snapshot.txt`);
fs-kit/same-file/trash→okio + posix; sha2→okio ByteString.sha256; regex→kotlin.text.Regex;
globset/ignore→own glob matcher; zstd/tree-sitter→cinterop to C libs; uuid→kotlin.uuid;
jiff/humantime→kotlinx-datetime; shlex→own; wasmi→not portable (see risks).

## Phases (commit per phase; `./gradlew macosArm64Test` green each phase)
### Phase 0 — scaffolding
- [x] Tests: port matching Rust unit + integration tests to kotlin.test; phase done only when they pass on the Mac (`./gradlew macosArm64Test`)
- [x] Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) + README section
- [x] Gradle KMP build, modules above, targets macosArm64/linuxX64/linuxArm64 (mingwX64 not yet added)
- [x] PORTING.md inventory, README stub
- [x] Build speed: configuration cache, build cache, parallel, daemon jvmargs, Kotlin daemon, host-only target by default (`-Prtok.allTargets=true` for others), debug-only test links, `kotlin.native.cacheKind`, `kotlin.incremental.native`, fine-grained modules, one test binary per module; measure clean + incremental debug build/test times and record below + docs/build-speed.md
- Acceptance: `./gradlew build` produces `rtok.kexe`; empty test suite green.
### Phase 1 — pure core (no IO)
- [x] Tests: port matching Rust unit + integration tests to kotlin.test; phase done only when they pass on the Mac (`./gradlew macosArm64Test`)
- [x] Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) + README section
- [x] plugin-sdk `Class`; config `Estimator`; `tokens.rs` (estimate, tokens_saved) + tests
- [x] `modes/` compress_prose (Lite/Full/Ultra), YAGNI ladder + all unit tests
- Acceptance: every Rust unit test of these files translated and passing.
### Phase 2 — rtok-config + rtok-log
- [x] Tests: port matching Rust unit + integration tests to kotlin.test; phase done only when they pass on the Mac (`./gradlew macosArm64Test`)
- [x] Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) + README section
- [x] root discovery (`root.rs`) + since (`since.rs`) + tests
- [x] TOML layers (`layers.rs`, figment → ktoml), `lib.rs` Config struct (2.7k lines), `validate.rs` + tests
- [x] rtok-log append/format/rotation (same line format, rotation files, flock lock) + tests
### Phase 3 — rtok-hook protocol + `rtok hook`
- [x] crates/rtok-hook wire protocol, fingerprint, home expansion, unix-socket client (ported early: rtok-config depends on it) + 6 tests
- [ ] src/hooks (resident server, deadline, push, types) and `rtok hook` command
- [ ] Tests: port matching Rust unit + integration tests to kotlin.test; phase done only when they pass on the Mac (`./gradlew macosArm64Test`)
- [ ] Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) + README section
- [x] host hook JSON in/out types + adapters for 9 hosts (`hooks/Types.kt`, `Names.kt`) — 18/18 types.rs tests
- [x] deadline.rs (3/3 tests), resident.rs server + `bind_private` (1/1 + 1 new socket round-trip test), process `environ()` + `flock` lock in rtok-sys (2 new)
- [x] push.rs — agent message push + render frame (5 tests)
- [x] hooks/mod.rs dispatch + `rtok hook` (+ `--serve` wiring) — 26 of 55 tests (host shapers 8, session/slow 6, cap_budget 3, push 5, strict/oversize/malformed 3, activity 1)
  - pending: 29 dispatch tests that need codex/cursor/cline fixtures, agent registration rows, worktree, otel, MCP wrap; `HookLinks` no-ops for worktree/otel/MCP until those phases
### Phase 4 — rtok-store (SQLite ledger) ✅
- [x] prerequisite: full rtok-plugin-sdk contract (Plugin, Ctx, Host traits, wire refs, MemoryHost) — 9 tests
- [x] Tests: port matching Rust unit + integration tests to kotlin.test; phase done only when they pass on the Mac (`./gradlew macosArm64Test`)
- [x] Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) + README section
- [x] sqlite3 cinterop (system libsqlite3 3.54, FTS5) + `Db` wrapper (prepare/bind/query, nested savepoints) — 2 tests
- [x] sha256 (`rtok.sys.Sha256`) for archive ids — 1 test
- [x] migrations embedded verbatim (52, generated at build) + diesel-compatible `__diesel_schema_migrations` (version = 4-digit prefix) + legacy `schema_migrations` bridge
- [x] handle.rs/lib.rs: open (dir 0700, files 0600, pragmas, retry on lock), migrate under BEGIN EXCLUSIVE, archive file write, retention score — handle tests 10
- [x] sanitize.rs — 11 (rstest cases folded, proptest → seeded random)
- [x] hook_fields.rs — 7 (3 pure + 4 store-backed)
- [x] retention.rs, cache.rs, kv — retention 5 (tests_schema minus the 2 symbol-root cases, + archive purge)
- [x] call_log, measure_rows, session_rows, archive_rows, note_store, observations, archive_summaries, models — tests_store 31
- [x] otel.rs — 8; task_id, task_claims, task_counters — 7
- [x] tests_archive (22 → 29: drift checks run against the Rust crate's schema.rs + schema_snapshot.txt copied as fixtures)
- [x] agents — 14 (humantime grammar in the standalone `:humantime` module, 3 own tests); worktree_claims + worktree_pending — 8 (+1 same_path); note_files — 4; host_usage — 1 (new: read-only URI round trip)
- [x] shared SQL helpers in `SqlExt.kt` (`inList`), replacing three hand-rolled placeholder lists
- [x] messages — 7 (+1: unread counts, JSON omits a missing task)
- [x] fs stat helpers (`isDir`, `fileLen`, `fileMode`) moved from the store into `rtok-sys` for reuse
- [x] projects — 7; symbols — 10 (+ the 2 symbol-root retention cases from tests_schema, now 7 of 7 there)
- [x] rtok-sys: `fileStat` (mtime ns, expect/actual per libc), `asciiLowercase`, `compareUtf8` (Rust byte order) — 2 tests
- [x] project_links — 5
- [x] adapter layer: SQLite driver behind `Db`/`DbDriver` (+1 test), Clikt behind `CliParser` (+1 test)
- [x] symbol_graph — 3; graph_events — 3 (+1: rows_json matches serde field order, keeps a null ref_id)
- [x] docs — 0 in Rust (plugin-driven); 2 new store-contract tests
- [x] embed — 16 (+1 slot/sign scheme check)
- [x] docs/store.md: layout, driver adapter, schema-drift check, limitations
- [x] config TOML via ktoml (Ivan approved) behind `libs/toml` (`TomlReader`/writer interface) + `libs/toml-ktoml` adapter; own Toml.kt and line editor removed (branch eval/ktoml, rebased + fast-forwarded) — toml-ktoml 12, rtok-config 92 → 91
- [x] SQLite via androidx.sqlite (Room KMP driver layer, bundled) behind `libs/db` interface + `libs/db-room` adapter; cinterop removed; schema drift tests unchanged and passing — db-room 5 (2 moved from store DbTest)
- [x] tooling: Kotest 6.2.6 (+KSP) next to kotlin.test, one root convention; Kotlin 2.2.20 → 2.2.21 (docs/testing.md) — RenderSpec 1
- [x] tooling: Dokka 2 API docs, one root convention, `./gradlew dokkaGenerate` → `build/dokka/html` (docs/api-docs.md); not part of build/test

### Phase 5 — reduction plugins (src/plugins: compress, json_tree, toon, read, cmd, inject, guard, memory, measure, docs, archive, checkpoint)
- [x] registry (`plugins/Registry.kt`, dispatch order) + measure + proxy manifests + `render.plugins_table` + `skip_word` — 3 (+1); catalogue test is an ordered-subset check until every plugin is in
- [x] prerequisite: src/project.rs (project name/key/dir) — 13
- [x] prerequisite: src/plugin.rs `Runtime` host over Store + log::record — 5
- [x] archive (1450 lines) — 24
  - CLI testing: `archive rewrite --stdin` — Kotest ArchiveCliSpec 5 + e2e ArchiveE2eSpec 3
  - pending: 3 wire-fixture tests (Phase 7 wires), P33 CTT gate (Phase 6 measure::jsonl)
- [x] toon (674) — 10
  - early from Phase 7: `proxy/Anthropic.kt` ToolResults view (tool_results, skills, live_blobs) — 2 (+ archive T55.15 image test)
  - pending: toon expand row via `rtok expand` (Phase 6)
- [x] json_tree (1237) — 15
  - pending: 3 MCP `shorten_result` tests (cmd/mcp)
- [x] compress (1075) — 16
  - [x] llm.rs Compressor HTTP + queue.rs (3 tests) — Ktor 3.3.3 behind libs/http (approved 2026-10-26)
- [x] docs (707) — 12
- [x] checkpoint (1932) — 27
- [x] inject (523) — 8
- [x] guard (2844) — 45 of 53 (+1 check, +1 types.rs view key); grep_symbol 8 pending (graph/tree-sitter); docs/guard.md; read::outline markdown digest ported (no tree-sitter needed); cmd `sh_quote`/`wrap_quote` in `plugins/cmd/Run.kt`
- [ ] read (4334) — 101
- [x] cmd (10098) — 207 Kotlin tests: rules 51, formatters 25, run 29, hook 25, compact 18, grep 10, logfmt 8, gh 7, loops 7, python 7, bounded 7, diffs 6, mise 4, filter 3; rtok-sys ProgressRunner +5; catfile (4) + cat re-read delta (1) blocked on tree-sitter / read cache (docs/cmd.md)
- [x] memory (5419) — 126 of 127 (scrub 2, admit 6, files 8, pack 7, observe 6, handoff 11, sync 15, export 3, import 6, extract 11, mod 51); `rtok memory` CLI (all but `status`); docs/memory.md
  - pending: handoff `pointers_carry_enclosing_symbol_ranges_when_indexed` (needs graph index, Phase 10); `memory status` (model::memory_status); `sync --stat` (change_preview Preview); MCP dispatch (rtok-mcp)
- [ ] graph (20084) — 275
- [x] wasm host (478) — 3 → 5 (+1 registry, +3 Chasm adapter); Chasm 2.3.0 behind libs/wasm, fuel 100M/call; docs/wasm-plugins.md
- [ ] Tests: port matching Rust unit + integration tests to kotlin.test; phase done only when they pass on the Mac (`./gradlew macosArm64Test`)
- [ ] Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) + README section
### Phase 6 — CLI surface (src/cli: stats, info, expand, filter, run, config, logs, report, completions, man)
- [x] CLI layer: own `CommandSpec`/`Invocation`/`CliIo`, exit codes 0/1/2, Clikt behind `CliktAdapter`; `plugins` (table), `config init/path/validate/set`
- [x] CLI testing: Kotest `CliSpec` (17, our layer: parsing incl. `--since`, errors/exit codes/messages, log/root/config) + `./gradlew e2eTest` (11, built rtok.kexe, real git main/worktree/submodule)
- [ ] CLI testing per subcommand (Kotest spec + e2e case each): stats (`--since` vs real repo), info, expand, filter, run, config show/get, plugins --json, logs, report, completions, man
- [ ] Tests: port matching Rust unit + integration tests to kotlin.test; phase done only when they pass on the Mac (`./gradlew macosArm64Test`)
- [ ] Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) + README section
### Phase 7 — proxy (Anthropic/OpenAI chat+responses/Gemini wire, route, gate, semantic cache) via Ktor
- [ ] Tests: port matching Rust unit + integration tests to kotlin.test; phase done only when they pass on the Mac (`./gradlew macosArm64Test`)
- [ ] Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) + README section
### Phase 8 — MCP server (rtok-mcp, src/mcp) stdio + HTTP
- [ ] Tests: port matching Rust unit + integration tests to kotlin.test; phase done only when they pass on the Mac (`./gradlew macosArm64Test`)
- [ ] Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) + README section
### Phase 9 — agents install/uninstall/doctor for all hosts (src/agents, src/doctor)
- [ ] Tests: port matching Rust unit + integration tests to kotlin.test; phase done only when they pass on the Mac (`./gradlew macosArm64Test`)
- [ ] Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) + README section
### Phase 10 — graph (tree-sitter cinterop), otel export, worktree/task/batch/demon/bench
- [ ] Tests: port matching Rust unit + integration tests to kotlin.test; phase done only when they pass on the Mac (`./gradlew macosArm64Test`)
- [ ] Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) + README section
### Phase 11 — docs (how it works, enable/disable, test, limitations) and size/test report
- [ ] Tests: port matching Rust unit + integration tests to kotlin.test; phase done only when they pass on the Mac (`./gradlew macosArm64Test`)
- [ ] Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) + README section

## Build timings (Mac, macosArm64 debug)
| Scenario | Time |
|---|---|
| clean `macosArm64Test` (first run, incl. Kotlin/Native toolchain + deps download) | 3 m 23 s |
| incremental (1 file touched) `macosArm64Test` | 5.5 s |
| incremental, phase 2 (all modules) `macosArm64Test` | 8.3 s |
| `linkDebugExecutable` + `linkReleaseExecutable` | 23 s |
| incremental, rtok-config source touched (`:rtok-config:macosArm64Test`) | 45–60 s (test-binary link dominates) |

## Test counts per phase
| Phase | Rust tests in scope | Kotlin tests passing |
|---|---|---|
| 0 | 0 | 0 |
| 1 | 23 (tokens 4, ladder 3, compress 16) | 23 |
| 2 | log 12, root 7, lib 33, layers 20 (rstest cases folded), validate 27, change-preview 4, since 5 (new) | 108 |
| 3 (partial) | types 18, deadline 3, resident 1 (+1 new), sys environ/lock 2 new — dispatch 55 waits for Phase 5 | 25 |
| 4 | plugin-sdk 9, sha256 1, db 2, handle 10, sanitize 11, hook_fields 7, store 31, otel 8, tasks 7, retention 5, archive 29, agents 14, worktree 9, note_files+host_usage 5, humantime 3, messages 8, projects 7, symbols 10, retention +2, sys text 2, project_links 5, driver 1, cli adapter 1, graph 7, docs 2, embed 17 | 213 |
| 5 (partial) | registry 3 (+1 skip_word), project 13, runtime 8 (6 ported + defer, fit_budget), archive 16 + pi 4 + ArchiveCliSpec 5, toon 12 + anthropic wire 2, json_tree 12, compress 6 + must_keep 3 + llm 5, checkpoint 27, inject 9, compress queue 3 + compressor 5 + http 4, docs 13 | 151 |
| tooling | toml-ktoml 12, rtok-config −1 (own TOML parser test removed) | 11 |
| tooling | db-room 5 (+3; 2 moved from store) | 3 |
| tooling | Kotest RenderSpec 1 | 1 |
| 6 (partial) | Kotest CliSpec 17, toml-ktoml trailing newline 1 (e2e 11 counted separately) | 18 |
| 5 wasm | wasm host 5, registry 1, Chasm adapter 3 | 9 |
| **total** | | **559** (table predates later phases; live totals: PORTING.md, 955 unit + 26 CLI + 14 e2e) |

## Test-porting
Each Rust `#[cfg(test)]` module → `src/commonTest` in the matching module; `tests/*.rs` (138 files)
→ `:rtok` integration tests driving the built binary. Track count in PORTING.md.

## Risks / non-portable
- wasm plugin host: Chasm interpreter (pure Kotlin) doubles the release binary; see Chasm status.
- tree-sitter: 13 grammars need cinterop + vendored C sources per target.
- rustls/webpki: replaced by platform TLS (Darwin/Curl engines) — behaviour differs.
- printpdf/resvg (report PDF/SVG): no native equivalent; report limited to text/JSON.
- Scope: ~200k lines; full parity is a multi-month effort (see PORTING.md for live status).

### Chasm status (2026-10-26)
- Interface in libs/wasm (`rtok.wasm`: WasmRuntime with fuel, WasmMemory, HostFunction, guestSlice).
- Adapter libs/wasm-chasm/ChasmRuntime.kt is written but not built. It is not in settings.gradle.kts.
- Blocker: every Chasm release needs Kotlin ≥ 2.2.0. Fuel metering, which the Rust test `endless_guest_runs_out_of_fuel` needs, exists only in Chasm ≥ 2.1, and those releases need Kotlin 2.4.10.
  - A trial on Kotlin 2.4.10 with KSP 2.3.12 passed 920/920 tests and was reverted.
  - Decided (Ivan): Kotlin bumped (now 2.4.21, KSP 2.3.12), Chasm 2.3.0 with fuel. Adapter built and tested (3 tests).
- Measured on M3 Max, release link, Kotlin 2.4.21: without the host 8,958,312 B / 50 s; with Chasm wired in 17,092,616 B / 125 s (+8.1 MB, +75 s).
  Fuel: 1M units of an endless loop trap after ~60 ms (debug test binary), so the 100M per-call budget is ~6 s debug worst case.
- Baseline before Chasm: release rtok.kexe is 8,739,016 B; release link takes 58 s.
