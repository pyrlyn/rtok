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
- [ ] push.rs (agent message push; needs Phase 4 store + render + plugin runtime)
- [ ] hooks/mod.rs dispatch (55 tests; needs Phase 5 plugins) and `rtok hook` command
### Phase 4 — rtok-store (SQLite ledger)
- [x] prerequisite: full rtok-plugin-sdk contract (Plugin, Ctx, Host traits, wire refs, MemoryHost) — 9 tests
- [ ] Tests: port matching Rust unit + integration tests to kotlin.test; phase done only when they pass on the Mac (`./gradlew macosArm64Test`)
- [ ] Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) + README section
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
- [ ] symbol_graph, docs, embed, graph_events
### Phase 5 — reduction plugins (src/plugins: compress, json_tree, toon, read, cmd, inject, guard, memory, measure, docs, archive, checkpoint)
- [ ] Tests: port matching Rust unit + integration tests to kotlin.test; phase done only when they pass on the Mac (`./gradlew macosArm64Test`)
- [ ] Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) + README section
### Phase 6 — CLI surface (src/cli: stats, info, expand, filter, run, config, logs, report, completions, man)
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
| 4 (partial) | plugin-sdk 9, sha256 1, db 2, handle 10, sanitize 11, hook_fields 7, store 31, otel 8, tasks 7, retention 5, archive 29, agents 14, worktree 9, note_files+host_usage 5, humantime 3, messages 8, projects 7, symbols 10, retention +2, sys text 2, project_links 5, driver 1, cli adapter 1 | 187 |
| **total** | | **349** |

## Test-porting
Each Rust `#[cfg(test)]` module → `src/commonTest` in the matching module; `tests/*.rs` (138 files)
→ `:rtok` integration tests driving the built binary. Track count in PORTING.md.

## Risks / non-portable
- wasmi plugin host (`plugins/wasm.rs`): no Kotlin/Native wasm runtime; would need wasm3/wamr cinterop.
- tree-sitter: 13 grammars need cinterop + vendored C sources per target.
- rustls/webpki: replaced by platform TLS (Darwin/Curl engines) — behaviour differs.
- printpdf/resvg (report PDF/SVG): no native equivalent; report limited to text/JSON.
- Scope: ~200k lines; full parity is a multi-month effort (see PORTING.md for live status).
