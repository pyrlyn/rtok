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
| (Rust dep `semver`) | `:semver` (`libs/semver`) | `semver` — own `VersionKey` (SemVer precedence) |
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
- [ ] graph (20084) — 275; walk 5, index 16 (12 Rust + batch + rows + rank-document 2; Rust-crate fixture pending), rank 10, cochange 5
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

## Session 2026-10-26 (afternoon) — where the port stands
Done today: Kotlin 2.4.21 / latest libs (e8c5c7a), mise toolchain (e0ad1be), Chasm adapter (7b4e2d5),
WASM plugin host (0c55807), hooks dispatcher + push + `rtok hook` (119c63c), smart test/build tasks (dfdb3cb).
Next, in order:
1. Phase 10 tree-sitter: `libs/treesitter` interface + `libs/treesitter-native` (cinterop over the vendored
   tree-sitter runtime + grammars; one registry with extension mappings and queries). Grammars: every
   grammar repo in github.com/tree-sitter, plus tree-sitter-grammars/tree-sitter-kotlin and the maintained
   Dart and Swift grammars. Measure size/build-time impact per grammar.
2. read plugin (101 tests), graph (275 tests incl. the memory handoff test), guard grep_symbol (8),
   cmd catfile (4) + cat re-read delta (1).
3. docs fetch with zstd behind our own interface; memory status, sync --stat, MCP wiring (Phase 8).
4. Remaining hooks tests (29) once worktree/otel/MCP land (`HookLinks`).
5. Final stage: completeness pass against Rust + code review (no separate preview deliverable).

## Session 2026-10-26 (evening) — where the port stopped
Done: tree-sitter interface + native adapter with 34 grammars (PR #3), `mise run coverage` (PR #4),
read `outline.rs` (10 tests) and `density.rs` (4), rtok-sys gitignore walk (5), review fixes (this PR).
Decision: Dropbox affectedmoduledetector rejected (archived, breaks the configuration cache, Android task
names only); `scripts/smart.py` stays (docs/toolchain.md).
Release binary: unchanged so far (17.9 MB) — no CLI/MCP path calls the read plugin yet, so K/N and ld
strip the grammars; measure again once `read`/`graph` are wired (all grammars = 70 MB of objects).
Read plugin finished (PR feat/read-rest): `Read.kt`, `Roots.kt`, `Search.kt`, `Cache.kt`, `Hook.kt`,
`rtok/Expand.kt` + `rtok expand`, `rtok/worktree/Git.kt` (porcelain list), `HostPaths.kt`; 84 read + 27 expand tests.
Graph started (merged with #9): `rtok/plugins/graph/Walk.kt` (`Matcher`, walk.rs, 5 tests).
Exact stop point: graph `index.rs` (Report, run/run_with/run_changed, extractor fingerprint, SYMBOL_BATCH_FILES
batching; needs a per-core parse worker pool and `outline` symbol rows with span/sig/doc) is next, then
text, resolve, tally, mod.rs tools, and the rest in dependency order (events, rank, blast, follow, drill, scope,
diff, export, health, lsp, watch, review, draw, capability, cochange, projects, jobs, status).
Release binary after read: 17.97 MB, `linkReleaseExecutableMacosArm64` 125 s wall (incl. compile); the grammars
are still stripped because no CLI/MCP path calls `read`/`outline` until `rtok mcp` dispatches the read tools.
Next, in order:
1. (done) read.
2. graph (275 tests; 20k Rust lines), grep_symbol (8), catfile (4) + cat re-read delta (1).
3. docs fetch + zstd behind an interface; memory status, sync --stat, MCP wiring; otel, worktree/task/batch/bench;
   29 hook dispatch tests; worktree host events via `rtok hook`.
4. Completeness pass against Rust and final code review.

- Fixed (#9): release-only K/N crash `Unexpected receiver type: kotlin.Char` in `rtok run cat` (boxed `Char?` in `Bounded.lex`). `./gradlew e2eTest -Pe2e.release` runs e2e against the release binary.
- Stop point: graph `Walk.kt` (Matcher) is merged with #9; next is `index.rs`, then text, resolve, tally, mod.rs.

## Session 2026-10-26 (late) — graph index
- `rtok/plugins/graph/Index.kt` (index.rs): run/runWith/runChanged/ensure, stat + sha gates, extractor fingerprint,
  SYMBOL_BATCH_FILES batching, `scoped` rows with span/hash/signature/doc, indexed HEAD. Parsing runs on
  `rtok.sys.eachOrdered` (one K/N Worker per core, results in walk order, bounded in-flight). Tags queries are
  compiled on the calling thread before the workers start.
- Rank refresh goes through `FileRanks` (no-op `FileRanks.None` until rank.rs lands; set `Index.ranks`).
- `Resolve.kt` (resolve.rs, 1 test), `Tally.kt` (tally.rs, 4 tests; `@ThreadLocal` record).
- Stop point (main 683ebd3, 1173 tests green): next `cochange.rs` + `rank.rs` (set `Index.ranks`; enables the 2
  pending index rank tests), then the mod.rs core types (`Filter`, `Mode`, `ExploreParts`, `backend_name`) that
  `text.rs` needs, then `text.rs`, `capability.rs`, the rest of mod.rs and the other submodules.

## Session 2026-10-26 (evening) — graph rank + cochange
- `Cochange.kt` (cochange.rs, 3 Rust tests + layout test; `impact` partner test waits for mod.rs `impact`),
  `Rank.kt` (rank.rs, 10 tests; `Index.ranks` defaults to `Rank`, both pending index rank tests enabled),
  `Git.kt` (mod.rs `git_stdout`/`git_stdout_result`, shared with `Index.rememberIndexedHead`).
- On-disk compat: `file_rank` JSON keeps the serde field names; the cochange `kv` doc keeps `pairs` as `[a,b,n]`.
- `Alerts.kt` (health.rs Kind/Alert/mirrored/all/describe/texts/reach/notice), `Score.kt` (health/score.rs), `Projects.resolve`; 6 tests. The checker loop (`Health` tick/backoff/run) is next.
- `HealthCheck.kt` (health.rs Health findings/backend backoff/tick/load/recovered/persist, scope_of, run loop); 6 tests. Manifest ref discovery (project::refs) is an injected `refWarnings` until it is ported; backend backoff tests wait for lsp.rs; stat has no 2 s timeout thread.
- `Projects.kt` (projects.rs rows/render/run/preview, `ProjectAction`), `Jobs.kt` (jobs.rs worker re-index), `rtok.table` (render.rs); 6 tests.
- `Dead.kt` (mod.rs dead_rows/dead_candidates/has_test_attr/is_test_path/is_support_path/rust_impls; affected/affected_from_paths/changed_starts/name_linked_tests/git_changed_files/format_affected); 4 tests.
- `graph/Scope.kt` (scope.rs: resolve, fan-out, callers/symbol/impact/explore/outline/dead/affected over linked projects; review_git waits for review.rs; relOf keeps absolute paths) + ScopeTest (12).
- `graph/Dispatch.kt` (mod.rs `call`: symbol/callers/impact/outline/explore over a scope, stale-HEAD prefix, outline kind filter) + DispatchTest (4).
- `project/Refs.kt` (project/refs.rs: cargo/npm+pnpm/go/python/submodule references) + RefsTest (9); `graph/Follow.kt` (follow.rs: follow/indexNew/refresh/report) + FollowTest (8); store `dropToollessSessionProjects`; health `refWarnings` now defaults to Refs. Lock-wait follow tests not ported (no hold_write_lock helper).
- `graph/Drill.kt` (drill.rs: files/defs/external nodes, calls/imports/implements/contains edges, focus neighbourhood, cap, scope search) + DrillTest (6).
- `graph/Export.kt` (export.rs: collect/graph/near, redact/redactor, read/parse, run/render/write, pageFile/downloadName, MCP call) + ExportTest (9). SVG via `ExportView.draw` hook (draw.rs pending); PNG needs a rasterizer dependency, so it errors; schema generation (schemars) not ported. Detekt findings baselined like #31. Also restores older session stop-point lines that an earlier global sed overwrote.
- Stop point: next the mod.rs core types (`Filter`, `Mode`, `ExploreParts`, `backend_name`), then `text.rs`,
  `capability.rs`, the rest of mod.rs and the other submodules.

## Session 2026-10-26 (evening) — graph core types
- `Core.kt` (mod.rs: `Filter`, `Mode`, `GraphCore.backendName/modeOf/indexFor/pendingPaths/withStale/
  withoutModeLine/lspNoneAnswer`, shared `graphCfg`), `Langs.kt` (lsp.rs `LANGS`/`EXTENSIONS`/`language_of`/
  `server_of`/`served_languages`); 5 tests (mode-of, filter, language tables, lsp-none, auto_index=false stale banner).
- Stop point: next `text.rs` (uses Mode/Filter/backend_name), then `capability.rs`, the mod.rs tools
  (symbol/callers/impact/explore with `ExploreParts`, `record_mode_row`), and the other submodules
  (events, blast, follow, drill, scope, diff, export, health, lsp, watch, review, draw, projects, jobs, status);
  then grep_symbol (8), catfile (4), cat re-read delta (1); docs fetch + zstd, memory status, sync --stat, MCP,
  otel, worktree/task/batch/bench, 29 hook dispatch tests, worktree events; release size re-measure; completeness pass.

## Session 2026-10-26 (night) — graph text + capability
- `Capability.kt` (capability.rs: record, mirror in `plugin_state` with serde field names, `ask`/`serverReady`/
  `held`/`reprobe`, `Refused`/`serverBroke`; probe is a `Probe` lambda until lsp.rs lands), `Text.kt` (text.rs,
  `Text.Explore`), `ExploreParts` in `Core.kt`, `rtok.sys.Mutex`. 6 capability + 8 text tests (door-level capability
  tests run through `ask`; the concurrency test waits for mod.rs `door`).
- Stop point: next the mod.rs tools (`door`, symbol/callers/impact/explore + `assemble_explore`, `record_mode_row`;
  enable the deferred cochange `impact` partner test), then events, blast, follow, drill, scope, diff, export, health,
  lsp, watch, review, draw, projects, jobs, status; then the rest of the list above.

## Session 2026-10-26 (evening) — graph tools door, symbol, callers
- `Tools.kt` (mod.rs: `text_or`, `lsp_or_tags`, `door`, `record_mode_row`, `tags_kind`/`text_kind`, `cap`/`cap_kind`,
  `symbol`/`symbol_tags`/`symbol_by_id`, `defs_text`, `body_lines`, `def_id`/`def_head`/`parse_symbol_id`, ambiguity
  marks, `callers`/`callers_tags`). The server side is an `LspBackend` interface (`LspBackend.None` until lsp.rs).
  7 tools tests + the concurrent-first-request capability test (test `support.parallel`).
- Stop point: next `impact` (`impact_filtered`/`impact_tags`, `with_cochange`; enable the deferred cochange impact
  partner test), then `explore` (`assemble_explore`, tags `ExploreParts`), `outline`, `call` dispatch; then events,
  blast, follow, drill, scope, diff, export, health, lsp, watch, review, draw, projects, jobs, status; then the rest of
  the list above.

## Session 2026-10-26 (late) — graph impact + blast
- `Impact.kt` (mod.rs `impact`/`impact_filtered`/`impact_tags`, `with_cochange`, `reverse_call_chain`, `impact_bfs`,
  `impact_walk_roots`, `WalkMemo`), `Blast.kt` (blast.rs budgeted render; `WalkRow`, `Budget`, `linesText`).
  `LspBackend.impact`. 4 impact + 5 blast tests; the deferred cochange impact partner test is on.
- `Explore.kt` (explore_tokens, assemble_explore/assemble_named, explore/explore_tags with FTS fallback, `TagsExplore`); 5 tests.
- `Status.kt` (status.rs collect/format_table, mod.rs `cli_root`); 2 tests. `rtok graph` CLI wiring still pending.
- `Events.kt` (events.rs `GraphCall` start/progress/end, target/symbols/backend); 6 tests (the web `coalesce`
  summary test waits for web live).
- `Outline.kt` (outline_in, outline_of_kind, symbol_arg_names, head_stale_prefix; `LspBackend.outline`/`outlineWorkspace`); 4 tests.
- Stop point: next diff, lsp, watch, review (+ Scope.reviewGit), draw; wire `rtok graph` into CLI; then grep_symbol, catfile, cat re-read, docs zstd, memory status, sync --stat, MCP, otel, worktree, task, batch, bench, hook dispatcher tests; release re-measure, completeness pass.
  a project scope), then `call` dispatch (needs scope); then
  follow, drill, scope, diff, export, health, lsp, watch, review, draw, projects, jobs (needs projects); then the rest.

## Session 2026-10-26 (night) — graph diff
- `Diff.kt` (diff.rs: git-side extraction via one `git cat-file --batch` fed from a temp file, index as the working
  side, changed/added/removed/moved/renamed, edges with renamed/moved callers followed, not-analysed files, per-project
  `PROJECT:REF`, saved-export compare + registry links, `DiffReport` JSON/page with `capped`, MCP `call`); 19 tests.
  `ExportView.parse` now refuses a bare word (kotlinx parses `/a/path` as a primitive; serde does not).
- `Draw.kt` (draw.rs SVG: degree-capped nodes, sunflower layout, legend, footer; `ExportView.draw` defaults to it, so `--format svg` works); 6 tests. PNG stays unported (needs a rasterizer dependency); the Rust PNG tests are deferred.
- `Review.kt` (review.rs: risk score, security tokens, `-U0` hunk parse, widen/merge, round-robin budget, text/JSON with serde key order) + `Scope.reviewGit`; 9 tests.
- `cli/GraphCmd.kt` (cli/graph.rs): `rtok graph index|dead|status|impact|projects [add|select|link|unlink|reindex|remove|list]|review|diff|export|affected`; clap conflicts/requires as usage errors (exit 2). CLI model: repeatable options (`OptSpec.Value(repeat)`, `Invocation.values`), `-o` short names, `CommandSpec.runsBare` (bare `graph projects` lists). GraphCliSpec (2). The index line prints bare (no `success_op` icon yet: there is no `ui/style` port); `--format png` fails like before (no rasterizer).
- `guard/GrepSymbol.kt` (grep_symbol.rs, T369): symbol-shaped Grep answered from the index (classifier, path scope, stale-row check, inject budget fit, checkout-root fallback), wired into `GuardPlugin.preTool`; 9 tests (classifier table + 7 Rust tests; the linked-worktree test waits for the worktree port).
- `cmd/CatFile.kt` (catfile.rs, T674): long `cat <source>` prints definitions with spans; `cat [-n] f` / `sed -n A,Bp f` re-reads go through the read cache (pointer when unchanged, delta when changed) in `emitFilteredTo`; 5 tests (4 catfile + the run.rs re-read delta).
- Stop point: next lsp, watch; then docs zstd, memory status, sync --stat, MCP, otel, worktree, task, batch, bench, hook dispatcher tests; release re-measure, completeness pass.

## Session 2026-10-26 (night) — graph lsp
- `rtok.sys.PipedChild` (piped stdin/stdout child: queued non-blocking stdin, `read(deadline)` over poll, `tryWait`,
  `kill`; cwd via `posix_spawn_file_actions_addchdir_np` in librtok_spawn; SIGPIPE ignored) + PipedTest (5).
  `mergedEnv` shared with `spawnDetached`.
- `LspWire.kt` (framing, file URIs, percent coding, `rel`, `sync_kind`, `kind_name`), `LspSession.kt` (Session:
  deadline requests without reader/writer threads, server replies, did_open/open/close/refresh_open, cached
  session `with`/`shutdown`), `Lsp.kt` (on_path with mise-shim/rustup-proxy checks, `rustup which`, probe, pick,
  state dirs, spawn + initialize, symbol/callers/impact/call paths/explore/outline). `Tools.lsp` now defaults to
  `Lsp`. LspTest (27: all Rust lsp.rs tests; the Windows drive test is not applicable).
- Deferred: `LspSession.shutdown()` must run when `rtok mcp` ends (MCP wiring); health-check backend backoff tests.
- Stop point: next watch; then docs zstd, memory status, sync --stat, MCP, otel, worktree, task, batch, bench, hook
  dispatcher tests; release re-measure, completeness pass.

## Session 2026-10-26 (night) — graph watch
- `rtok.sys.FsWatch` (notify's part): `watchRecursive` = FSEvents on macOS (file events on a dispatch queue into
  `FsEventQueue`, dropped/must-scan flags as rescan), inotify on Linux (watch per directory, new dirs added,
  overflow as rescan; not compiled locally, linux targets are off by default) + FsWatchTest (3).
- `graph/Watch.kt` (watch.rs: run, runScope with one worker per scope member and 2 s rescope, pump, settle,
  root `.gitignore`, absorbEvent, pending sync) + WatchTest (14: all Rust watch.rs tests, FSEvents ones included).
  `Matcher.root` is public.
- Deferred: start `Watch.runScope` from `rtok mcp` when `[plugins.graph] watch = "notify"` (MCP wiring).
- Stop point: next docs zstd; then memory status, sync --stat, MCP, otel, worktree, task, batch, bench, hook
  dispatcher tests; release re-measure, completeness pass.

## Session 2026-10-26 (night) — docs fetch + zstd
- `:zstd` (interface `ZstdDecoder`, `ZstdException`) + `:zstd-native` (zstd 1.5.7 pinned in `zstd.json` by
  tarball sha256, fetched into build/vendor; lib/common + lib/decompress compiled without asm/threads into
  librtok_zstd.a; streaming `decodeAll` over concatenated frames with a 2 GiB cap) + NativeZstdTest (6).
- `rtok.http.HttpClient.get` (Ktor `send` shared by post/get) + test; `docs.httpGet` (rtok UA, 30 s),
  `docs.runFetch`, CLI `rtok docs fetch <name>` (cli/docs.rs). DocsTest +1 (real zstd body).
  Live check: `rtok docs fetch zstd` in a copy of the Rust Cargo.lock → `cached zstd 0.14.2 (60 items)`.
- Stop point: next memory status; then sync --stat, MCP, otel, worktree, task, batch, bench, hook dispatcher
  tests; release re-measure, completeness pass.

## Session: memory status
- `rtok memory status` (--project, --since, --json): MemoryStatus.kt ports model::memory_status + memory_status.rs; MemoryStatusTest (3).
- Full suite: 1423 tests, 0 failures.
- Stop point: next `sync --stat`.

## Session: sync --stat
- `rtok.sys.Preview`/`PreviewMode` (change-preview `Preview`: edits, removals, `--stat` lines with 40-mark bar cap, totals) + PreviewTest (5); `ChangePreview.counts`.
- `rtok.StatPreview` (stat_preview.rs: process-wide switch, colour-stripping `sides`, `show`/`showFile`/`shown`) + StatPreviewTest (4); `rtok memory sync --dry-run --stat`, totals line now closes the dry-run diff.
- Deferred: `--stat` on config/doctor/agents dry runs lands with those commands.
- Stop point: next MCP (incl. `LspSession.shutdown()` on exit, `Watch.runScope` for watch="notify").

## Session: MCP core (stdio server)
- `rtok/mcp`: `Protocol` (rpc errors, protocol negotiation, required-field gate with `anyOf`, search/docs_query aliases, read-only annotations, mem hit line), `LineReader` (8 MiB cap, lossy UTF-8), `Server` (initialize/ping/tools/list/tools/call, batches, notifications, roots handshake), `Tools` (expand, ping, mem_*, handoff, docs_*, read/search/tree, graph tools via `GraphTools`), `Record` (mcp_call rows + I/O + 3 token rows), `Roots`, `Mcp.run`/`Mcp.call`; CLI `rtok mcp [--call TOOL --args JSON]`.
- `LspSession.shutdown()` now runs on every `rtok mcp` exit (served and `--call`); `Watch.runScope` starts on a worker when `[plugins.graph] watch` is not `off`.
- Tests: ProtocolTest (9), LineReaderTest (2), ServerTest (11). Live: initialize + ping over stdio with an isolated HOME.
- Deferred to the next MCP chunks: graph plugin registration (graph tools are dispatched but not yet listed — no `GraphPlugin.mcpTools`), whoami/agents/messages tools + agent link, worktree_* and task_* tools (with worktree/task), `--http`, `rtok mcp ping`, the foreign-server wrapper (`rtok mcp -- <cmd>`), retention/otel tickers, health-check loop, follow refresh, rooted-path annotation (mcp/paths.rs), description token cap test.
- Stop point: next MCP chunk 2 (graph plugin tool defs + paths.rs), then otel.

## Session: MCP chunk 2a — graph tool descriptors + paths.rs
- `rtok.plugins.graph.GraphPlugin` (mod.rs `GraphPlugin`: manifest, dashboard page, Edit/Write `markSymbolsStale`,
  the 7 `mcp_tools` descriptors) registered in `plugins.all()` after memory, so `tools/list` now lists the graph tools.
- `rtok.mcp.Paths` (mcp/paths.rs: `isRooted`, `anchorOutline`, `annotate`); `Server.invokeRooted` enters the caller
  root through `read.Roots.enterCaller` (caller dir hook is `{ null }` until the agent link lands). PathsTest (6).
- Full suite: 1460 tests, 0 failures. Deferred: graph session-start repo map (`repo_map`, needs checkpoint `last_paths`).
- Stop point: next whoami/agents/messages tools + agent link (feeds `callerDir`), then `--http`, `mcp ping`,
  `mcp -- <cmd>` wrapper, server background jobs; then otel.

## Session: MCP chunk 2b — agent link + whoami/agents/messages tools
- `rtok.agents.AgentView` (agent_view.rs: `AgentState`, `AgentView`, `SessionView` with serde-flatten JSON),
  `rtok.model.Agents` (model/agents.rs: worktree label, nest, session views, `agentSessions`/`agentShow` with claims +
  unread enrich, `cleanStatus`/`setStatus`; takes the open `Store` instead of reopening one),
  `rtok.agents.AgentLink` (agents/link.rs `resolve` env/ancestor/cwd/own rules, `Link`, `Rule`, `Caller`;
  shell_agent.rs `shellAgent`). Hookless detection is the `HookSupport` interface (`Unknown` = every host hooked)
  until the agent host registry is ported.
- `rtok.mcp.AgentSession` (mcp.rs link cache, `caller_dir` 5 s TTL, `agent`/`agent_for`/`whoami`), `rtok.mcp.AgentTools`
  (mcp/agents.rs + mcp/messages.rs: agents_list, agent_show, agent_status_set, agent_send, agent_inbox; `withAgent`).
  `Server` lists whoami + the 5 agent tools after ping (Rust order), links on `initialize`; `Paths.invoke` is
  mcp.rs `invoke_rooted` and now feeds the caller root from the agent link.
- Tests: LinkTest (19), AgentsTest (5), AgentToolsTest (6). Full suite: 1491, 0 failures. Detekt: test-only
  MagicNumber/TooManyFunctions baselined.
- Deferred: hookless host table (agent registry), `rtok agents` CLI commands on this model.
- Stop point (superseded below): next `--http`, `rtok mcp ping`, `mcp -- <cmd>` wrapper, server background jobs; then otel.

## Session: MCP chunk 3 — `rtok mcp -- <server>` wrapper (wrap.rs)
- `rtok.mcp.FrameReader`/`Framing` (wrap.rs `read_frame`/`write_frame`, fed incrementally: line JSON, `Content-Length`,
  Raw for malformed/oversized/short headers), `rtok.mcp.Wrap` (`run`, `Pending` note/take, `shortenResult` with the
  `[mcp]` cmd rule, archive + expand trailer, json_tree fold). Single-threaded poll loop over our stdin and the
  server's stdout: `PipedChild` gains `inheritStderr`, `awaitReadable`, `finishInput`, `waitExit`.
- CLI: `rtok mcp -- <argv>` (variadic `server`), `--call` with a wrapped server is refused like Rust.
- Tests: WrapTest (10: the 8 Rust cases + byte-at-a-time feed + isError), e2e wrapper spec (cut + pass-through + exit code).
  Full suite: 1501 unit/CLI, 0 failures; e2e 16.
- Ivan approved ktor-server (CIO, pinned) for `--http`, behind our own HTTP server interface in its own module.
- Stop point: next `rtok mcp --http` (mcp/http.rs), then agent host registry → `rtok mcp ping` → HookSupport → `rtok agents`
  CLI; server background jobs; otel.

## Session: MCP chunk 4 — `rtok mcp --http` (mcp/http.rs)
- New modules: `:http-server` (rtok.http.server: `HttpServer`, `RequestHandler`, `ServerRequest/Response`, serialized
  handler calls, 413 body cap) and `:http-server-ktor` (`KtorHttpServer`, ktor-server-core + CIO 3.6.0 pinned; approved by Ivan).
- `rtok.mcp.Http` (http.rs): `Addr` (SocketAddr parse, loopback/unspecified), `PublicUrl` (host + origin), `transport`
  allow-lists, `exposureGuard`, `bearerToken`, constant-time `bearerMatches`, `httpServer` (fresh `mcp-http-<uuid>`
  session, `restrictArchives`, AGENT_BOUND dropped, `http_tools` as the allow-list), `Endpoint` (rmcp stateless JSON
  mode: 401 Bearer, 404, 403 Host/Origin, 405 non-POST, 406 Accept, 415 Content-Type, 400 MCP-Protocol-Version,
  202 for notifications), `serveBlocking` (deny secrets, log the URL, never the token). `Server.handleLine(stateless)`.
- CLI: `rtok mcp --http [ADDR]` (`OptSpec.Value(optional)` → clikt `optionalValue`); refuses `--` and `--call`.
- Tests: HttpTest (9: the 6 Rust cases + Addr parse + endpoint gates + live socket initialize/401),
  KtorHttpServerTest (1). Full suite 1511, 0 failures. Deferred: retention + otel tickers on `--http` (with otel).
- Stop point: next agent host registry → `rtok mcp ping` → HookSupport → `rtok agents` CLI; server background jobs; otel.

## Session: agents chunk 1 — host registry descriptors (agents/mod.rs + 36 host folders)
- `rtok.agents.Hosts` (`host`, `ALL` in rtok-config `HOSTS` order, `MODULES`, `wants`), `Kind`, `Variant`, `Support`
  (Yes/Flag/Offer/No), `SupportRule` (first-match arms of each host's `support`), `Host` (`support`, `hookless`,
  `shared`). The 36 rows (variants + support arms, incl. `support_mcp_only` hosts) were generated from the Rust
  sources and checked by HostsTest (5).
- `HostHookSupport` (link.rs `hookless`) is now the default for `AgentLink.resolve` and `AgentSession`: zed/warp and
  other hookless hosts register their agent from the MCP process. Full suite 1516, 0 failures.
- Deferred (agents chunk 2+): per-host `files`/`markers`/`installed`/`apply`, MCP config paths and writers
  (agents/mcp.rs), `find_bin`/`probe_installed`/`app_version`, plugin surfaces.
- Stop point: next `rtok mcp ping` (mcp/ping.rs: needs host MCP config paths from agents/mcp.rs), then `rtok agents` CLI.

## Session: mcp ping chunk 1 — host config shapes (mcp/ping.rs, agents/jsonc.rs parse)
- `rtok.agents.Jsonc` (parse with comments + trailing commas, `stripComments`), `rtok.mcp.Ping` (`SHAPES` for 18 hosts,
  `HEADLESS` command lines, `pingText`, `promptFor`, `launchFromRead`/`launchFromBody` → `Launch`).
- Tests: PingConfigTest (3, incl. the Rust per-host fixture case), JsoncTest (2). Full suite 1521, 0 failures.
- Stop point: next ping process side (`probe` via doctor mcp_roundtrip, headless `capture`, `select`/report/JSON, CLI)
  which needs per-host `files`/`installed` from agents chunk 2.

## Session: agents chunk 2a — MCP config core (crates/rtok-mcp)
- New module `:rtok-mcp` (registry.rs, spec.rs, config.rs, status.rs, ops.rs): `McpSpec`, `McpFs`, `McpConfig`
  (strict JSON / JSONC / TOML), `JsoncEdit` (lossless JSONC property set/remove), `TomlEdit` (`[<key>.rtok]` table,
  implicit parents, one-line inline entries), `status`, `apply` + `Report`. `Jsonc` moved here from `rtok.agents`.
- `:rtok-agent-sdk` Owned.kt: `parseStrictJson`, `judgeOwned`, `keepEdited`, `runsBin`, `withoutDefaultType`,
  `confirmed`/`accepted`/`answer`.
- Tests: OpsTest 8 (7 Rust + strict-JSON refusal), EditorsTest 4, JsoncTest 2 (moved). docs/mcp-config.md.
- Stop point: next agents/mcp.rs (`McpSurface`, `McpRow`, per-host `surfaces`, doctor lines, `run`/`sync`) over the
  36 hosts' paths, then per-host files/installed/apply, `find_bin`, `app_version`; then the ping process side.

## Session: agents chunk 2b — MCP surfaces and rows (agents/mcp.rs status side)
- `rtok.agents.McpFiles` (every host variant's MCP file + servers key, one table row per host; VS Code stable/insiders),
  `McpPaths` (OS defaults: VS Code user dirs, Claude/OpenCode desktop, CodeBuddy candidates), `HostPath.kt`
  (join/parent/with_file_name, `loneSibling`).
- `McpSurfaces` (`surfaces` with plugin/Grok `[compat.claude]` import, `status`, `spawnable`, `doctorLine`,
  `jsonSpec`, `tilde`), `McpRow` (serves/found/text/JSON), `EntryState`, `McpArgv` (`argvOf`, `staleDiff`,
  `unresolvedCommand`), `McpEntries` (`hasEntry`/`hasTomlEntry`), `isRtokBin`, `resolveMcpCommand`, `findOnPath`, `DiskFs`.
- Tests: McpEntriesTest 9, McpSurfacesTest 8 (incl. the Rust every-host × 6-case surface test, row sources).
- docs/build-speed.md: current release size 87.6 MB (`all`) / 98.4 MB (`none`) after #8.
- Deferred: `rows`/`own_row` (per-host `installed`), `doctor_lines`, `run`/`sync`/`install`/`remove` (apply ctx),
  plugin detection (`plugin_surfaces` + installed), Windows path branches (no Windows target).
- Stop point: next per-host files/installed/apply, `find_bin`, `app_version`; then doctor_lines + run/sync; ping process side.

## Session: agents chunk 2c — host binary lookup (agents/mod.rs find_bin/app_version, locate.rs lookup)
- `rtok.agents.HostBins` (`RTOK_HOST_SANDBOX`, `expandSpec`/`expandApp`, `under`, `findBin`, `findOnPath`,
  `probeInstalled`, `hostProgram`), `Locate` (`key`, `variantFor`, `locate`, `present`, `appPaths`, `programs`, `cli`,
  `cliProgram`), `AppVersion` (`of` with a 3 s `--version` probe limit, `looksLikeAVersion`).
- Tests: HostBinsTest 4, LocateTest 3, AppVersionTest 3 (incl. wrapper noise and a CLI that never answers).
- Deferred: `PATHEXT` / `.exe` probing (no Windows target); locate.rs `resolve_input`/`ask_*`/`absent`/`not_found`
  (with `rtok agents` CLI); `run_cli` (with plugin offers).
- Stop point: next per-host `files`/`installed`/`apply` (Agent interface over the 36 hosts), then `doctor_lines` +
  MCP `run`/`sync`, ping process side, `rtok agents` CLI.

## Session: agents chunk 3a — Agent contract + MCP-only hosts (augment, crush, droid, junie, kiro, openhands, roo, warp)
- `:rtok-agent-sdk` `HostJson` (lib.rs `read_json`/`write_json`/`edit_json`, `register_server`, `unregister_server`,
  `unregister_owned`; the two-space pretty printer, now shared with rtok-mcp's `McpConfig`).
- `rtok.agents.Agent` (`files`/`installed`/`apply`), `Mode`, `applyOf(cfg)`, `StdioMcp` (register/unregister in a key),
  `McpOnlyAgent` (`mcp_only_file!`, `installed_mcp_only`, `apply_mcp_only`), `Agents` registry (8 hosts).
- Tests: McpOnlyAgentTest 7 (the Rust per-host default-path, dry-run, stdio roundtrip and apply-idempotent cases run
  over all 8 hosts, plus foreign-entry leave and `[setup] mcp = false`).
- Stop point: next host group 3b — JSON hook hosts (windsurf, cline, qwen, gemini, codewhale, commandcode, copilot,
  devin, kimi), then 3c (opencode/mimo/kilo/zcode/zed/amp/omp/pi/aider/continue/goose/reasonix/antigravity/vscode/
  codebuddy) and 3d (claude, cursor, codex, grok).

## Session: agents chunk 3b-1 — windsurf (MCP-only, desktop)
- `windsurf` joins `Agents.MCP_ONLY` (`setup.windsurf.config_path`, stdio entry); McpOnlyAgentTest now covers 9 hosts.
- Group 3b split further: the hook hosts (cline, qwen, gemini, codewhale, commandcode, copilot, devin, kimi) need
  `hook_events.rs` (host event table) and `HostPlugin` (plugin-file copy + `ours`/`offer`) first.
- Stop point: next 3b-2 = `hook_events.rs` + `HostPlugin`, then cline; 3b-3 = JSON settings hooks (gemini, qwen,
  codewhale, commandcode, copilot, devin, kimi).

## Session: agents chunk 3b-2a — PluginLink (agent SDK link/copy cycle)
- `:rtok-agent-sdk` `PluginLink` (`linked`/`ours`/`run` with `CopyFix`/`upToDate`/`health`), `LinkHealth`, `KETCH_INSTALL`,
  `OWNED_MARKER`, `inVersionedStore`, `treeCopies`, `copyOwned`, `unlinkAt`; rtok-sys `EntryKind.kt` (lstat kind,
  `readLink`, `symlink`, mode-keeping `copyFile`).
- Tests: PluginLinkTest 7 + PluginCopyTest 7 (every Rust `plugin_link_*`/owned-copy/health case; the two foreign-remove
  cases merged into one).
- Deferred: `SkillCopy`/`skill_plan` (with skills), Windows owned-copy-by-default branch (no Windows target).
- Stop point: next 3b-2b = `hook_events.rs`, `plugin_src` resolution, `HostPlugin`, then cline.

## Session: agents chunk 3b-2b — hook events table, plugin sources, HostPlugin, cline
- `rtok.agents.HookEvents` (hook_events.rs: every host's rows, `forHost`/`installerRows`/`installed`/`entries`).
- `PluginSrc` (`resolve_plugin_src`: beside exe, symlink target, `<prefix>/share/rtok`, ketch store by version then
  newest by SemVer, dev root, beside-exe fallback; `joinRel`), `VersionKey` (own SemVer precedence, no dependency),
  `PluginSource`/`ShippedPlugins` (injectable source root; the Rust `CARGO_MANIFEST_DIR` fallback has no native
  counterpart, so tests pass their own root).
- `HostPlugin` (plugin.rs: `path`/`linked`/`ours`/`offer` fail-open/`problem`), `Cline` (5 hook links from the table,
  MCP on the CLI and VS Code extension files), `mentionsRtok` shared with `McpOnlyAgent`; `Agents` registry has cline.
- Tests: HookEventsTest 3, PluginSrcTest 8 (all Rust plugin_src/ketch/join_rel cases + SemVer order), ClineTest 6
  (4 Rust + MCP surfaces + registry).
- Deferred: `HostPlugin::all`, the default-install table test (with the plugin hosts), `markers`/`support`/`shared`
  (in `Hosts`).
- Stop point: next 3b-3 = JSON settings hooks (gemini, qwen, codewhale, commandcode, copilot, devin, kimi).

## Session: libs/semver — VersionKey moved to its own module
- `VersionKey` (SemVer precedence, from #58) moved out of `rtok/agents/PluginSrc.kt` into the leaf module `:semver`
  (`libs/semver`, package `semver`, no dependencies); `PluginSrc` imports it. Test moved and extended: 1 → 3.
- Stop point: next 3b-3a = shared helpers from agents/mod.rs, then gemini + qwen.

## Session: agents chunk 3b-3a — shared host helpers (agents/mod.rs)
- `rtok/agents/HostHelpers.kt`: `HookLine` (`shell_quote_bin`, `rtok_command` — bare `rtok` on unix, `rtok_hook_bin`,
  `hook_resolver`, `unquote_bin`), `StripReport` (`takes_hook`, `removed_report`, `with_kept`), `HostCli` (`cli_label`,
  `run_cli`), `PluginOffers` (`manifest_names`, `PluginOffer`, `offer_plugin`, `print_offer`). `run_cli` returns null
  on success, else the failure text (`CliCall`). rtok-sys `ProgressRunner.env` (Rust `.env`) for the config-dir override.
- Tests: 13 (2 ported `shell_quote_bin_*`, 11 own).
- Stop point: next 3b-3b = gemini + qwen (JSON settings hooks), then codewhale, commandcode, copilot, devin, kimi.

## Session: agents chunk 3b-3b — Claude-layout hook JSON walk (claude/mod.rs)
- `ClaudeHooks.kt` (`HookForm`, `HookSpec`, `HookEdit`, `command`/`is_ours`, `command_for`/`is_ours_for`, `show`, the
  `run` body as `edit`), `HookInsert.kt` (`insert_ours_as`, `has_ours`, `prune_ours`), `HookStrip.kt` (`strip_ours_as`).
  Shared by every host with Claude's `hooks.<event>[]` layout (qwen, devin, codebuddy, codex, claude itself).
  Immutable kotlinx JSON: keys keep insertion order (Rust serde_json sorts them; same semantics, other byte order).
- Tests: 7 (5 ported from claude/mod.rs, 2 own).
- Stop point: next 3b-3c = `d21_plugin_apply` + qwen, then gemini (own BeforeTool/ms form).

## Session: agents chunk 3b-3c — D21 plugin apply + qwen
- `D21Plugin.kt` (`D21Plugin`, `d21_plugin_apply`, `no_extra` as the default `extra`), `Qwen.kt` (settings.json
  Claude-layout hooks via `ClaudeHooks`, stdio MCP, `qwen extensions link` behind `--yes`, `QWEN_HOME` for a moved
  dir); registered in `Agents`.
- Tests: 4 (3 ported, 1 own); `extension_hooks_follow_the_event_table` reads the Rust tree's plugins/qwen, not ported.
- Stop point: next 3b-3d = gemini (own BeforeTool/ms hook form, `extensions link`), then codewhale, commandcode,
  copilot, devin, kimi.

## Session: agents chunk 3b-3d — gemini (and the shared extension-host shape)
- `ExtensionHost.kt` (`ExtensionSpec`, `ExtensionHost`): settings.json hooks + stdio `mcpServers.rtok` + D21
  `extensions link` behind `--yes`, home variable for a moved dir. `Qwen` now sits on it; `Gemini.kt` adds Gemini's
  own `HookForm` (Gemini event key, `hook <ClaudeEvent> --host gemini`, no matcher, `timeout` in ms,
  `GEMINI_CLI_HOME`); registered in `Agents`.
- Tests: 5 (3 ported, 2 own); support/t275/hooks_doc stay in the Rust tree (Hosts/plugins).
- Stop point: next 3b-3e = codewhale, commandcode, copilot, then devin, kimi.

## Session: agents chunk 3b-3d fix — gemini hook walk parity with Rust
- #63 ran Gemini through the Claude walk, which pruned by `(event, matcher)` and treated any `matcher: ""` entry as
  unchanged on strip. `HookSpec.matcherless` now gives Gemini's walk (gemini/mod.rs): ours found under any matcher,
  no prune, strip counts only a `hooks`-only entry as unchanged (else `keep_edited`).
- Tests: 3 own (matcher slot kept, unlisted event untouched, user-edited entries left); stale test uses the real spec.
- Stop point: next 3b-3e = codewhale, commandcode, copilot, then devin, kimi.

## Session: agents chunk 3b-3e — devin
- `Devin.kt`: `config.json` Claude-layout hooks via `ClaudeHooks.formFor("devin")` (Devin event rows, `timeout` s),
  Windows `%APPDATA%` redirect for the XDG default, `mcp_config.json` stdio entry, `--yes` print-only plugin offer,
  `hooksDoc` for `plugins/devin/hooks.json`; registered in `Agents`.
- Tests: 5 (3 ported, 2 own); plugin_manifest_matches_the_installer reads the Rust tree's plugins/devin, not ported.
- Found: codewhale and kimi edit TOML `[[array-of-tables]]` keeping comments (toml_edit); the Kotlin side has only
  `rtok-mcp` `TomlEdit` (one `[a.b]` table). They need a lossless array-of-tables editor first (own module).
- Stop point: next = commandcode, copilot (JSON), then the TOML array-of-tables editor, codewhale, kimi.

## Session: agents chunk 3b-3f — commandcode
- `CommandCode.kt`: `settings.json` hooks in Command Code's own walk (one definition per matcher, lifecycle rows with no
  matcher, fixed 5 s `timeout`, no prune/refresh, strip by `<rtok-bin> hook <Event> --host commandcode`, empty
  definitions and an empty `hooks` key go), stdio `mcpServers.rtok` in `mcp.json` (independent of the plugin, D33),
  `plugins/commandcode` linked to `<dir>/plugins/rtok` behind `--yes`; registered in `Agents`.
- Tests: 9 (all Rust cases but the dispatcher payload one). Deferred: `skill::sync` (with skills).
- Stop point: next = copilot (hooks/rtok.json, `mcp-config.json` LocalAllTools, `copilot plugin`), then TOML editor check.

## Session: agents chunk 3b-3g — copilot (and the MCP run path)
- `McpRun.kt` (agents/mcp.rs `Writer`, `run`, `sync`, `install`, `remove`): rtok-mcp `apply` on the real disk through the
  SDK `write` (atomic, backup, dry run). `Copilot.kt`: rtok-owned `hooks/rtok.json` (`version: 1`, bash + PowerShell
  resolvers, `timeoutSec`, removed with a backup), `mcp-config.json` `LocalAllTools` entry via `McpRun`, `copilot plugin
  install|uninstall` behind `--yes` as the D21 hooks unit (`COPILOT_HOME` for a moved dir); registered in `Agents`.
- Tests: 6 (all Rust cases but the PreCompact dispatcher one; the T275 lifecycle spelled out inline).
- Deferred: `skill::sync` tail, `assert_json_entry_lifecycle` helper.
- Stop point: next = TOML `[[array-of-tables]]` editor check, then codewhale + kimi (or group 3c).

## Session: libs/toml-aot — lossless TOML table / array-of-tables editor
- Checked first: no popular, maintained Kotlin Multiplatform TOML library edits while keeping comments. ktoml (in use)
  only encodes/decodes; tomlkt is the same; toml-edit-kotlin (a KMP port of toml_edit) has 0 stars, no release and is
  marked in progress, so it does not qualify.
- New leaf module `:toml-aot` (`libs/toml-aot`, package `tomlaot`, no dependencies): `TomlDoc` (`parse`, `arrayOfTables`,
  `table`, `shape`, `retain`, `push`, `ensureTable`, byte-for-byte `toString`), `TomlTable` (`keys`, `size`, `get`/`str`/
  `int`, in-place `set` keeping the line's comment), `TomlValue` (Str/Int/Bool/Raw, basic-string quoting). Comment and
  blank lines before a header go with that table (toml_edit decor); multi-line strings and arrays never read as headers.
- Tests: 10.
- Stop point: next = codewhale (`[hooks] enabled` + `[[hooks.hooks]]`), then kimi (`[[hooks]]`).

## Session: agents chunk 3b-3h — codewhale
- `HostToml.kt` (`load_toml`: absent = empty, invalid = error, via `rtok.config.Toml` check then `:toml-aot` `TomlDoc`),
  `Codewhale.kt` (`[hooks] enabled` only when absent + one `[[hooks.hooks]]` `message_submit` table, strip by
  `takes_hook`, `mcp.json` `{type: "stdio", ...}` through `StdioMcp.register(typed = true)`); registered in `Agents`.
- Tests: 5 (2 ported + enabled=false kept with its comment, broken config untouched, edited table left).
- Stop point: next = kimi (`[[hooks]]` with refresh/prune, MCP spec, plugin marker offer), then group 3c.

## Session: agents chunk 3b-3i — kimi (group 3b done)
- `Kimi.kt`: `[[hooks]]` tables via `:toml-aot` (Claude spelling, refresh stale binary/timeout in place, prune unlisted
  pairs, strip by `takes_hook`), `mcp.json` `{command, args}` through `McpRun` (`EntryShape.Command`), printed
  `/plugins install <plugins/kimi>` offer, D21 strip while `plugins/managed/rtok/kimi.plugin.json` exists; registered.
- Tests: 8. Group 3b (JSON/TOML hook hosts) is complete.
- Stop point: next group 3c (opencode/mimo/kilo/zcode/zed/amp/omp/pi/aider/continue/goose/reasonix/antigravity/vscode/
  codebuddy), then 3d (claude, cursor, codex, grok).

## Session: agents chunk 3c-1 — pi, omp, amp
- `Pi.kt`: `plugins/pi` linked as `<setup.pi.extensions_path>/rtok`, by default (T164); no MCP, no hooks.
- `Omp.kt`: the same tree behind `--yes` plus stdio `mcpServers.rtok` (no `type`) in `setup.omp.mcp_path`.
- `Amp.kt`: `amp.mcpServers.rtok` as one dotted segment via the shared MCP core in JSONC (comments kept), lone
  `settings.jsonc` edited in place; `McpEntries.hasEntryAt` takes a segment list.
- Tests: PiTest 5, AmpTest 4.
- Stop point: next 3c-2 = opencode, mimo, kilo.

## Session: agents chunk 3c-2 — opencode, kilo, mimo
- `OpenCode.kt`: `OpenCodeFamily` (local argv `mcp.rtok` via the shared MCP core, `plugins/opencode/rtok.ts` linked
  beside the config by default), `OpenCode` (+ `env.OPENAI_BASE_URL` proxy, Desktop file), `Kilo`, `Mimo` (lone
  `mimocode.jsonc` edited as JSONC). `:rtok-mcp` `EntryShape.LocalArgv` = `{type: local, command: [argv], enabled}`.
- Tests: OpenCodeTest 6.
- Stop point: next 3c-3 = zcode, zed.

## Session: agents chunk 3c-3 — zcode, zed
- `Zcode.kt`: Claude-layout hooks under `hooks.events` (`hooks.enabled`, `timeoutMs`, absolute binary via
  `HookLine.desktopCommand`), `mcp.servers.rtok`, `plugins/zcode` linked to `plugins/local/rtok` + `plugins.dirs`;
  linked plugin = only hook path (D21), MCP independent (D33).
- `Zed.kt`: `context_servers.rtok` in JSONC through `McpSurfaces.jsoncSpec` (Amp moved onto it too).
- Tests: ZcodeTest 5, ZedTest 4.
- Stop point: next 3c-4 = aider, continue, goose.

## Session: agents chunk 3c-4 — aider, reasonix, antigravity
- `Aider.kt`: `openai-api-base` line edit (`YamlKeyLine`), ownership by loopback authority (`ProxyUrl.loopbackPort`,
  own code, no URL library); `ProxyUrl.openai` now shared with OpenCode.
- `Reasonix.kt`: `[[plugins]]` table named rtok via `:toml-aot`, `judgeOwned` on remove, other `plugins` shapes refused.
- `Antigravity.kt`: CLI printed `agy plugin install` offer (staged copy kept), Desktop link behind `--yes`.
- continue and goose are held: both parse YAML (`serde_saphyr` in Rust); no YAML library is in the build yet, so a
  dependency needs Ivan's approval first.
- Tests: AiderTest 4, ReasonixTest 5, AntigravityTest 4.
- Stop point: next 3c-5 = vscode, codebuddy; then continue/goose once YAML is decided.

## Session: agents chunk 3c-5 — codebuddy
- `Codebuddy.kt`: CLI + WorkBuddy dirs, `hook <event> --host codebuddy` hooks in `settings.json`, typed
  `mcpServers.rtok` (JSONC) in the first existing MCP file, `plugins/codebuddy` via `codebuddy plugin marketplace add`
  + `plugin install` behind `--yes` (`CODEBUDDY_CONFIG_DIR` for a non-default dir), D21 singleton over hooks.
- Tests: CodebuddyTest 5.
- Stop point: next 3c-6 = vscode; then continue/goose (YAML decision), then 3d.

## Session: agents chunk 3c-6 — vscode
- `Vscode.kt`: `servers.rtok` (typed stdio) in each profile's `mcp.json` (D33); behind `--yes` `plugins/claude` linked at
  `<user dir>/plugins/rtok` and registered in `chat.pluginLocations` through `JsoncEdit` (now public, takes any value).
- Tests: VscodeTest 4.
- Group 3c done except continue and goose (YAML library decision pending).
- Stop point: next 3d = claude, cursor, codex, grok.

## Session: agents chunk 3d-1 — claude (install side)
- `Claude.kt`: Claude-layout hooks in `settings.json` (Claude rows minus the plugin-only Worktree rows), typed
  `mcpServers.rtok` in `~/.claude.json` and, with the absolute binary, in `claude_desktop_config.json`; `plugins/claude`
  from the GitHub marketplace `pyrlyn/rtok` by default once `claude` is on PATH (`MarketplaceState` absent/current/stale,
  repoint, forced reinstall steps, `REINSTALL_FAILED`); installed plugin = only hook path (D21), MCP independent (T275).
- `ProxyEnv.kt` (proxy_env.rs): `env.<KEY>` set/strip shared by Claude (`ANTHROPIC_BASE_URL`, strip only ours) and OpenCode.
- Tests: ClaudeTest 8.
- Stop point: next 3d-2 = claude `plugin_update` (plugin_version receipt/version file, plugin_install `claude_install`),
  then 3d-3 = claude `migrate` (`--replace`); skill sync stays pending for every host. Then grok, cursor, codex; then
  `libs/yaml-aot` (own lossless YAML editor, Ivan: no YAML library) and continue/goose.

## Session: agents chunk 3d-2 — claude plugin update (T279)
- `PluginVersion.kt` (plugin_version.rs): `Source`, `VersionFile`, `Receipt`/`ReceiptEntry` (sorted keys, receipt under the
  OS state dir or `pluginReceiptPath`), `installed`, `available` (GitHub/marketplace catalog/local + `git describe`),
  `localVersion`, `decide` (SemVer precedence via `:semver`, build metadata compared on equal precedence), `nowIso`.
- `ClaudeUpdate.kt`: `plugin_update` (skip / update in place / reinstall, stale marketplace, new local build, receipt
  record and delete on `REINSTALL_FAILED`) and plugin_install.rs `claude_install`; `agents update claude` uses it.
- Tests: ClaudeUpdateTest 6, PluginVersionTest 2. Windows receipt path (LOCALAPPDATA) not ported (no Windows target).
- Stop point: next 3d-3 = claude `migrate` (`--replace`), then grok, cursor, codex; then `libs/yaml-aot` + continue/goose.

## Session: agents chunk 3d-3 — claude migrate (`--replace`)
- `ClaudeMigrate.kt` (claude/migrate.rs): legacy token hooks out (rtk, lean-ctx, caveman, python-launcher), legacy
  loopback proxy 8788/8787 retargeted with its path/query kept, lean-ctx/code-review-graph MCP dropped in both files,
  `--yes` or `--dry-run` required; `Claude.apply(Replace)` runs it. Claude host done except skill sync (pending for all).
- Tests: ClaudeMigrateTest 3.
- Stop point: next 3d-4 = grok, then cursor, codex; then `libs/yaml-aot` + continue/goose.

## Session: agents chunk 3d-4 — grok
- `Grok.kt`: `[mcp_servers.rtok]` in `config.toml` through `McpRun` (TOML), kept when present except the pre-`--host`
  legacy entry (T283.2), skipped while `[compat.claude] mcps` + rtok's Claude MCP cover it; hooks never written (one
  set: plugin or the Claude import, said in a note); `grok plugin install <plugins/grok> --trust` offered behind
  `--yes`, remove keeps Grok's copy. Windows skip note not ported (no Windows target).
- Tests: GrokTest 5.
- Stop point: next 3d-5 = cursor, then codex; then `libs/yaml-aot` + continue/goose.

## Session: agents chunk 3d-5 — cursor
- `Cursor.kt`: flat `{version, hooks.<event>[].command}` in `hooks.json` from `HookEvents` (installer rows only, retired
  `beforeSubmitPrompt` taken back), a bare `rtok` wrapped as `{ <resolver>; }` for Cursor's heredoc, stale lines
  rewritten in their slot (T242.5), edited ones asked about on remove (T246.6); typed `mcpServers.rtok` in the sibling
  `mcp.json` (D33); `plugins/cursor` linked into `plugins/local/rtok` by default (T164), then the only hook path (D21).
  Windows copy fix not ported (no Windows target).
- Tests: CursorTest 6.
- Stop point: next 3d-6 = codex (several PRs); then `libs/yaml-aot` + continue/goose.

## Session: agents chunk 3d-6a — codex (MCP + hooks)
- `Codex.kt`: `[mcp_servers.rtok]` through `TomlDoc` (comments, other servers kept, no bare `[mcp_servers]`) with
  `default_tools_approval_mode = "writes"` (T528, added to older entries, a user's value kept), bare `rtok` repointed
  (T932), a pre-`--host` entry replaced, remove judged by `judgeOwned` ignoring our approval value; `hooks.json` with the
  `codex` rows in Claude's layout, `--host codex` spelling, pre-T645 compaction lines rewritten in place.
- `:toml-aot` `TomlDoc.removeTable` (table and its subtree); `HostToml.toJson`/`strings` shared with Reasonix.
- Tests: CodexTest 7, TomlDocTest +1.
- Stop point: next 3d-6b = codex proxy (`[model_providers.rtok]`, `# rtok: was` restore), then 3d-6c = codex plugin
  (marketplace add/repoint, `plugin_update`); then `libs/yaml-aot` + continue/goose.

## Session: agents chunk 3d-6b — codex proxy
- `CodexProxy.kt`: `model_provider = "rtok"` + `[model_providers.rtok] {name, base_url}` behind `--proxy` (removed on any
  remove); the replaced value kept as `# rtok: was "<v>"` on that line and restored byte for byte, a re-install keeps the
  original; a hand-edited provider table left unless `keepEdited` says remove (T307).
- `:toml-aot`: `TomlDoc.root`, `TomlTable.set(key, value, comment)`, `remove`, `comment`.
- Tests: CodexProxyTest 4, TomlDocTest +1.
- Stop point: next 3d-6c = codex plugin (marketplace add/repoint, `plugin_update`); then `libs/yaml-aot` + continue/goose.

## Session: agents chunk 3d-6c — codex plugin
- `CodexPlugin.kt`: `plugins/codex` from the GitHub marketplace `pyrlyn/rtok` by default once `codex` is on PATH
  (`marketplace add` skipped when current, stale/old-repo marketplace re-pointed, an enabled plugin under a stale one
  reinstalled), read from Codex's own `[marketplaces.rtok]` / `[plugins."rtok@rtok"]`; `CODEX_HOME` for a non-default
  dir; failing or missing CLI keeps the offer. `update` = `marketplace upgrade rtok`, cache bytes compared, full
  reinstall on a failed upgrade (T242.4). Enabled plugin = only hook path (D21), MCP independent (T275).
- Codex host done except skill sync (pending for every host).
- Tests: CodexPluginTest 5.
- Stop point: next `libs/yaml-aot` (own lossless YAML editor), then continue and goose.

## Session: libs/yaml-aot — own lossless YAML module (Ivan: no YAML library)
- `:yaml-aot` (no dependencies), replaces `serde_saphyr` + agents/yaml_text.rs: `Yaml.parse` reads block maps and
  sequences (compact `- key: v`), flow collections, plain/quoted scalars with the core schema, folded plain lines,
  `|`/`>` block scalars, comments, `---`; anchors, aliases, tags, directives and complex keys are refused, never
  guessed. `YamlText` is the line editor: `lines` (endings kept), `scalar`, `indentOf`, `skippable`, `eolOf`, `splice`,
  `block` (a top-level key and its lines), `lastContent`. Writing never re-serialises: callers swap only their lines.
- Tests: YamlTest 6.
- Stop point: next continue and goose (MCP only) on `:yaml-aot`.

## Session: agents chunk 3c-7 — continue, goose (on `:yaml-aot`)
- `YamlMcp.kt` (shared): read/validate through `Yaml.parse` (an unreadable document is refused, never rewritten),
  `toJson`, `core` keys, `render`, owned removal through `judgeOwned`; `applyMcpOnly` now shared with Reasonix.
- `Continue.kt`: the `- name: rtok` item of the block `mcpServers:` list (also at the key's own indent, `[]` or a bare
  header), fresh file with `name`/`version`/`schema`, the header dropped with its last item, extra item keys not an edit.
- `Goose.kt`: `extensions.rtok` `{name, type, cmd, args, enabled}`, Goose's re-saved defaults not an edit, the header
  dropped when it held nothing else; flow or scalar `extensions` refused.
- `:yaml-aot` `YamlText.block` end fixed for an empty block.
- Tests: ContinueTest 8, GooseTest 8, YamlTest +2 asserts. Every host in the registry now has its install side.
- Stop point: agents phase left: skill sync for every host (pending), then the rest of phase 10/11 per this plan.

## Session: agents — skill sync for every host (agents/skill.rs, SDK SkillCopy)
- `:rtok-agent-sdk` `SkillCopy.kt`: `SkillPlan`, `SkillView` (`ours`), `skillPlan`, `SkillCopy` (`view` never follows a
  link, `run` with dry-run label, edited-copy question), `editedSinceMarked`; reuses `treeCopies`/`copyOwned`/`unlinkAt`.
- `rtok.agents.Skills`: `SKILLS`, `SKILL_DESC_MAX`, `root`/`dest`/`label` for the 8 documented hosts (+ `antigravity-cli`),
  `src` (hub, then the pi-bundled copy), `sync`, pre-T380 `worktrees` retirement. A missing hub tree (a dev build with
  no shipped `skills/`) installs nothing. Wired as in Rust: claude (every mode, desktop too), codex, commandcode,
  copilot (D21 `extra`), opencode, pi, antigravity (desktop and CLI roots). Cursor has a root but no caller, as in Rust.
- Tests: SkillCopyTest 9, SkillsTest 6; Pi/Antigravity tests take the extra skill line.
- Stop point: next agents leftovers (doctor MCP lines, `rtok mcp ping` server side, `rtok agents` CLI), then phase 10.

## Session: agents — doctor MCP lines (agents/mcp.rs rows/own_row/doctor_lines)
- `rtok.agents.McpDoctor`: `rows` (surfaces with the MCP-serving plugin and Grok's `[compat.claude]` import, `status` +
  `spawnable` on the real disk), `ownRow` (continue/goose/reasonix through their `installed`; none without MCP),
  `doctorLines` (off under `[setup] mcp = false`), `hostDoctorLines` (installed variants or present apps, `--cli/--desktop`
  flag unless shared). `Agent.pluginServesMcp` = Rust `plugin_surfaces` ∋ Mcp (codebuddy, gemini, antigravity, pi, devin).
- Tests: McpDoctorTest 2. `rtok doctor` itself is not ported yet; it calls `McpDoctor.doctorLines` when it lands.
- Stop point: next `rtok mcp ping` process side, then `rtok agents` CLI.

## Session: `rtok mcp ping` process side + `rtok mcp --host` (mcp/ping.rs, doctor.rs mcp_roundtrip, cli/mcp.rs)
- `rtok.mcp.Roundtrip` (doctor `mcp_roundtrip` over `PipedChild`: handshake, `tools/list`, optional call id 3, deadline),
  `rtok.mcp.PingRun` (`namedHosts`, `select`, `check` headless-chat or server-only, cursor-agent→agent fallback,
  `probeServer`/`pingReply`, `renderText`, `rowJson`, `captureCli`), CLI `rtok mcp ping [agent] [--cli] [--desktop]
  [--timeout SECS] [--json]`.
- Found and fixed: `rtok mcp` had no `--host` (T283.1), so every entry the installers write (`mcp --host <host>`) failed
  to start; `--host` now overlays `[hook] host` like `rtok hook --host`.
- Tests: PingRunTest 6, McpPingE2eSpec 2 (live spawn of the built binary through a Windsurf config).
- Stop point: next `rtok agents` CLI (list/install/update/remove/doctor over `Agents`), then phase 10 leftovers.

## Session: agents — install engine (agents/mod.rs rows, block, run/apply_all, list/info)
- `Agent.pluginSurfaces` (Rust `plugin_surfaces`) replaces the MCP-only flag; every host now names its plugin surfaces
  as in Rust. Parity fix: codewhale's plugin serves MCP too, so its MCP rows now credit an installed plugin.
- `Modules` (module rows, lines, summary, expected, missing, mcp.rs `lines`), `PluginRows` (`reaches`, rows, lines),
  `Blocks.block` with `Outcome`, `AgentsRun` (`resolve`, `absent`/`notFound`, `run`: refuse unknown/absent hosts,
  back up every file up front, apply, read back, keep copies only on change; `wouldChange`, `applyAll`, `carryFlags`),
  `AgentsList` (`installedHosts`, `list`, `listIds`, `info`). `AgentsRun.Env` stubs the machine in tests.
- Output is Rust's pipe form (marks, no ANSI colour or operation icons). Not ported yet: `ensure_hook_client_link`
  (T357), the host progress bar, outdated.rs `plugin_status` note on the plugin row.
- Tests: ModulesTest 5, InstallTest 6, InstallListTest 2.
- Test count check: 1734 → 1730 between reports was a counting difference, not lost tests: `@Test` in sources went
  1731 (75100fb) → 1754 (2c536ea) with no `@Test` removed.
- Stop point: next `rtok agents list|info|install|update|remove` CLI over `AgentsRun`/`AgentsList`.

## Session: agents — `rtok agents install|uninstall|remove|update|list|info` CLI
- `AgentsCommand` (command.rs `parse_hosts`, `install`, `update`, `apply_hosts`; agents_host.rs `setup_flags` as a
  `[setup]` overlay), `cli/AgentsCmd.kt` (`setup` and `remove` as aliases; `--stat` needs `--dry-run`; `--source`
  checked; a failed Claude plugin reinstall exits 1 after printing). `update` names the missing host's key on stderr
  before the run refuses it, as in Rust. docs/agents.md.
- Tests: CommandTest 5, AgentsCliSpec 3 (install/info/update/remove against a Windsurf config in a scratch home).
- Stop point: next the other `rtok agents` subcommands (outdated/`update --check`, list/info `--json`, sessions,
  usage, junk, whoami, show, status, send, inbox), then restart.rs and the rest of phase 10.

## Session: agents — `rtok agents list|info --json` (model.rs `agent_row`, `agents_list`, `agents_listed`)
- `rtok.agents.AgentsJson`: one row per host × variant in Rust's key order (`host`, `kind`, `name`, `present`, `app`,
  `version`, `config`, `modules`, `mcp`, `plugins`), snake_case states, serde_json's 2-space pretty form. Modules, MCP
  surfaces and plugins only for a present app. `plugin_version`/`plugin_source` wait for outdated.rs `plugin_status`.
- Tests: AgentsJsonTest 2, AgentsCliSpec (info/list `--json` against Windsurf).
- Stop point: next `rtok agents outdated` / `update --check` (outdated.rs, with `plugin_version`/`plugin_source` in
  the JSON rows), then sessions, usage, junk, whoami, show, status, send, inbox, `--project`, restart.rs.

## Session: agents — `rtok agents outdated` / `update --check` (outdated.rs, plugin_install.rs)
- `rtok.agents.PluginInstall` (`install_probe`: the receipt row first, else each host's layout table: Claude's record,
  the Codex/CodeBuddy cache by SemVer (T628), linked trees, Gemini/Copilot manifest scans, Kimi, VS Code, Antigravity;
  `manifestVersion`, `cacheProbe`). `VERSION_DIR_ORDER` (mod.rs `version_dir_key`) now shared with `ketchStorePlugin`.
- `rtok.agents.Outdated`: `report`, `installedPlugin`, `pluginStatus` (T382 note), `human` table, compact `json`,
  `EXIT_OUTDATED` 10. CLI `rtok agents outdated [host] [--cli|--desktop|--all] [--json] [--exit-code]` and
  `update --check`. The `plugin` row of list/info carries the note; JSON rows carry `plugin_version`/`plugin_source`.
- Tests: OutdatedTest 8, AgentsCliSpec +1.
- Stop point: next sessions, usage, junk, whoami, show, status, send, inbox, `--project`, restart.rs.

## Session: agents — `rtok agents sessions [watch]` (render.rs sessions_table/tick, log.rs watch_loop)
- `rtok.agents.SessionsTable` (`duration`, `ago`, agent cells, `render`, `Ticker` with `keep` for a missed poll),
  `rtok.log` `WatchTick`/`watchLoop`/`repaint` (TTY repaint, pipe appends fresh rows, ends when reparented).
  `CliIo.tty`/`flush`. CLI `rtok agents sessions [--all] [--json]` and `sessions watch` over `model.Agents.agentSessions`.
- Tests: SessionsTableTest 6, AgentsCliSpec +1.
- Stop point: next whoami, show, status, send, inbox, then usage, junk, `--project`, restart.rs.

## Session: agents — whoami, show, status, send, inbox (command.rs, render.rs whoami/show text)
- `rtok.agents.AgentMessages` (`caller`, `whoami`, `statusCaller`, `send` with `--all-live` by project, `inbox` framed
  or JSON with `framed`, `sendArgs`), `rtok.agents.AgentText` (`whoami`, `show`). CLI `whoami`, `show`, `status`,
  `send`, `inbox` over the existing store and `model.Agents` (`agentShow`, `setStatus`).
- Tests: AgentMessagesTest 5, AgentsCliSpec +1.
- Stop point: next usage (usage.rs), junk (junk*.rs), `--project`, restart.rs, then the rest of phase 10.


## Session: measure — session logs for `rtok agents usage --source logs` (measure/usage.rs, jsonl.rs, codex.rs)
- New package `rtok.measure`: `Jsonl` (the usage side of jsonl.rs: one `Usage` per turn, a repeated `message.id` is a
  duplicate, a non-UTF-8 line is malformed; the tool/image/injection fields wait for `rtok stats`), `Codex`
  (`jsonlPaths`/`pathsWithExt` canonical walk with links last, `requests` from `token_count` lines), `isSubagent`,
  `UsageLogs.read` (Claude Code, Codex, and droid/grok/zcode/antigravity named as unsupported), `JsonFields` helpers.
  Timestamps via stdlib `kotlin.time.Instant` (no new dependency).
- Tests: UsageLogsTest 3.
- Stop point: next the readers of measure/usage/ (sqlite opencode/kilo, copilot, gemini, pi, kimi), then the
  `rtok agents usage` report and CLI (agents/usage.rs, cli/agents.rs), junk, `--project`, restart.rs.

## Session: measure — per-host usage readers (measure/usage/: sqlite, copilot, gemini, pi, kimi)
- `rtok.measure.usage`: `Sqlite` (OpenCode/Kilo `<host>[-channel].db`, read-only percent-escaped `file:` URI over
  `readHostMessages`), `Copilot` (last `session.shutdown` per session, `tokenDetails` over `usage`), `Gemini` (jsonl and
  legacy json chats, one request per message id), `Pi` (fork dedupe by id + timestamp), `Kimi` (`usage.record`). Shared
  `sessionFiles`/`readEach`/`stem`; `UsageLogs.read` now calls them in the Rust order.
- `parseJson` rejects a bare word: kotlinx reads `garbage` as an unquoted literal where serde fails.
- Tests: SqliteUsageTest 4, CopilotUsageTest 3, GeminiUsageTest 2, PiUsageTest 4, KimiUsageTest 2.
- Stop point: next the `rtok agents usage` report and CLI (agents/usage.rs, `[agents.usage]` flags, zone handling),
  then junk, `--project`, restart.rs.

## Session: agents — `rtok agents usage` (agents/usage.rs, cli/agents.rs usage, measure/stats.rs `row_cost`)
- `rtok.agents.Usage` (`[agents.usage]` checks, window from a date in the zone or a duration, rtok/logs/both sources,
  through-rtok tokens, coverage, ledger savings priced at the host's input rate, dearest-first sort), `UsageFormat`
  (`price` with provider prefix and date suffix, `units`, `usd`, `grouped`), `UsageText` (screen, `--unpriced`, JSON in
  the Rust struct order with flattened rows). `rtok.measure.rowCost`/`round6` (half away from zero, as Rust).
- `rtok.sys.Zone`/`CivilDate`: IANA zones from the system tzdata via `localtime_r`/`mktime` with `TZ` swapped for the
  call (no new dependency). `Invocation.conflict` moved from GraphCmd to Spec.kt for `--daily`/`--monthly`.
- CLI `rtok agents usage [--source] [--host] [--since] [--until] [--daily|--monthly] [--by] [--tz] [--unpriced] [--json]`.
  Fixture `fixtures/usage-logs` copied from the Rust trycmd input (claude, codex).
- Tests: UsageReportTest 8, UsageLogsReportTest 5, ZoneTest 2, AgentsCliSpec +1.
- Stop point: next junk (junk*.rs, several PRs), `--project`, restart.rs, then the rest of phase 10.

## Session: agents — junk 1: the host junk map (junk_map.rs)
- `rtok.agents.JunkMap` (`specs` per host from research.md §22, documented vs listed-only, VS Code per-commit
  `CachedData`, Electron folders, `NO_ROWS`) and `JunkRoots` (`{home}`, `{claude}`, `{codex}`, XDG, `{copilot_cache}`,
  `{rtok_cache}` with env overrides; relative ones ignored).
- Tests: JunkMapTest 7.
- Stop point: next junk 2 = junk_cache.rs (the `cache` item model; needs worktree clean `Policy`/`kept_because` and
  `usage_until`), then junk_kinds, junk_review, junk_sessions, junk_worktrees, junk_items, junk.rs (list/report),
  junk_clear, CLI; then `--project`, restart.rs, the rest of phase 10.

## Session: json — one strict JSON tree reader (serde_json strictness)
- New leaf module `:json-strict` (`libs/json-strict`, `rtok.json.StrictJson.parse`/`parseOrNull`): kotlinx's tree
  reader takes unquoted words (`garbage`, `NaN`, `01`, `TRUE`) as literals even with `isLenient = false`; serde rejects
  them. Every `parseToJsonElement` in main code (rtok, plugin-sdk, agent-sdk, store, mcp, wasm; 40+ files) now goes
  through it; `parseJson` (measure), `SerdeJson.parse` and `parseStrictJson` delegate to it. JSONC keeps its comment and
  trailing-comma stripping, then parses strictly (as agents/jsonc.rs feeds serde). Typed `decodeFromString` calls were
  already strict (`isLenient = false`, concrete field types).
- Fixed on the way: Cursor `tool_output` of a bare word became an unquoted literal; now it stays a string, as in Rust.
- Tests: StrictJsonTest 2 (garbage table), TypesTest +2.
- Stop point: next junk 2 = junk_cache.rs (`Policy`/`kept_because`/`usage_until`), then the rest of junk, `--project`,
  restart.rs, the rest of phase 10.

## Session: agents — junk 2: the `cache` kind (junk_cache.rs, worktree clean `Policy`/`kept_because`, list.rs walk)
- `rtok.sys`: `Meta`/`symlinkMeta` (lstat with blocks, nlink, dev, ino), `setModified` (utimensat), `readDir` (errno
  kind; `listDir` keeps NotFound), `ioKind`, `DiskUsage`/`diskUsageUntil` (fs-kit `tree::usage_until`, hard links once).
- `rtok.worktree`: `isCacheDir`, `Cache`, `Usage`, `usage`, `usageUntil` (list.rs walk: tagged roots, nested checkouts
  skipped, incomplete reads), `Policy`, `keptBecause`, `AGE_UNKNOWN`/`KEPT_IDLE`/`KEPT_CURRENT` (clean.rs pure half).
- `rtok.agents`: `JunkCache` constants, `JunkItem`, `OwnedCache`, `JunkCtx`, `cacheItems`, `dropNested`, `junkItem`.
- Tests: JunkCacheTest 4, CleanTest 3, DiskUsageTest 2.
- Stop point: next junk_kinds.rs, then junk_review, junk_sessions, junk_worktrees, junk_items, junk.rs (list/report),
  junk_clear, CLI; then `--project`, restart.rs, the rest of phase 10.

## Session: agents — junk 3: the leftover kinds (junk_kinds.rs)
- `rtok.sys`: `lockHeld` (flock probe, read-write then read-only), `processAlive` (signal 0), `listDirOrEmpty`.
- `rtok.agents`: `JunkKinds` constants (`NOT_DOCUMENTED`, `PACKAGE_LOCKS`, `DEPS`, `BUILD_DIRS`), `foundItems`
  (locks/swap/index/backups), `tempItems`, `agedItems`, `buildItems` (tagged `build`, listed-only `build`/`deps`),
  `stagingCaches` (T279), `staleCodeCaches` (§22.2); file predicates and swap/lock reasons in `JunkFinds.kt`.
  `agent_worktrees`/`in_hosts` move to the junk_worktrees PR (they need `junk_worktrees::worktrees`).
- Tests: JunkKindsTest 5, JunkFindsTest 3.
- Stop point: next junk_worktrees (+ `agent_worktrees`), junk_review, junk_sessions, junk_items, junk.rs
  (list/report), junk_clear, CLI; then `--project`, restart.rs, the rest of phase 10.

## Session: agents — junk 4: the `sessions` kind (junk_sessions.rs)
- `rtok.agents`: `JunkSessions` (`IN_USE`, `isSessionId`), `sessionItems` (Claude Code transcript, `<session>/`,
  `file-history/<session>/`, one verdict per session), `retention` (Claude `cleanupPeriodDays`, Gemini
  `general.sessionRetention.maxAge`); `JunkClear.kt` starts with `newest`; `JunkCache.AGENT_SCAN_LIMIT`.
- Junk order change: junk_worktrees needs the worktree module (list rows, gc judge, ops, remove::detach), so it moves
  after the phase-10 worktree port; junk.rs report runs without the `stale-worktrees` rows until then.
- Tests: JunkSessionsTest 5.
- Stop point: next junk_review, junk_items, junk.rs (list/report), junk_clear, CLI; then `--project`, restart.rs,
  worktree (then junk_worktrees + `agent_worktrees`), the rest of phase 10.

## Session: libs/globset (own globset 0.4.20 port)
- `:globset` (no dependencies): `Glob.new`/`GlobOptions`/`GlobSet`/`GlobError` with globset's parser, `**` rules,
  nested `{}` alternates, classes, escapes, error messages; `(?-u)^…$` byte semantics as a Thompson NFA.
- Tests: GlobsetCasesTest 1 (globset's 125 Unix `matches!`/`nmatches!` cases), GlobTest 4.
- Stop point: next junk_review (uses `:globset` for `[agents.junk] exclude`), junk_items, junk.rs (list/report),
  junk_clear, CLI; then `--project`, restart.rs, worktree, junk_worktrees + `agent_worktrees`, the rest of phase 10.

## Session: agents — junk 5: the review kinds (junk_review.rs)
- `rtok.agents`: `JunkReview` (`EXTRA`, `EXCLUDED`, `SNAPSHOT`, `REPORT_EXT`), `logItems`, `backupItems`,
  `snapshotItems`, `crashDumpItems`, `processNames`, `diagnosticReports`, `extraItems`, `junkHours`; `exclude`
  (`[agents.junk] exclude` on `:globset`, a folder holding a match is kept, a bad glob keeps all) in `JunkExclude.kt`.
- Tests: JunkReviewTest 8.
- Stop point: next junk_items, junk.rs (list/report), junk_clear, CLI; then `--project`, restart.rs, worktree,
  junk_worktrees + `agent_worktrees`, the rest of phase 10.

## Session: agents — junk 6: the report and the item breakdown (junk.rs, junk_items.rs)
- `rtok.agents`: `Junk` (`KINDS`, `report`/`reportInRepo`/`reportWith`, rtok's folders and owned caches, project LSP
  dirs, `kindRows`, `topLevelBytes`), `JunkOwn` (T182 `scan`/`run`/`toTable`, `removalPreview`), `JunkHosts` (host
  folders, marker dirs, symlink targets, shared folders, every kind per host), `JunkList` (`toList`, OSC 8
  `folderLink`), `JunkItems` (`JunkView`, `JunkSort`, `JunkRow`, `groups`, `json`, `stampPlanned`, `whenUsed`).
- `Agent.markers` (default `files`; opencode, mimo, vscode, antigravity, commandcode, omp, pi, zcode, grok, cursor,
  cline add their plugin dirs); `rtok.worktree.notedTable`; `Zone.minute`.
- `reportInRepo` runs without agent worktrees and `stale-worktrees` rows until the worktree port.
- Tests: JunkTest 7, JunkItemsTest 6.
- Stop point: next junk_clear.rs (`Planned`, plan/apply, `Row::from(&Planned)`), then the `agents junk list|report|clear`
  CLI; then `--project`, restart.rs, worktree, junk_worktrees + `agent_worktrees`, the rest of phase 10.

## Session: agents — restart 1: the process check (restart.rs `Procs`, `host_running`)
- `rtok.agents`: `Procs` (`appRunning`, `binRunning`), `RealProcs` (macOS `osascript` app check, `pgrep -x`),
  `desktopAppName`, `runningDesktopApp`, `runningCliBin`, `hostRunning`. Quit/reopen and `with_restart` wait for the
  full restart port.
- Tests: RestartTest 2.
- Stop point: next junk_clear.rs (`Planned`, plan/apply), the `agents junk list|report|clear` CLI; then `--project`,
  the full restart.rs, worktree, junk_worktrees + `agent_worktrees`, the rest of phase 10.

## Session: agents — junk 7: clear with filters (junk_clear.rs)
- `rtok.agents`: `JunkFilter` (`isT182`, class gate), `Planned` (`row` = `Row::from(&Planned)`), `Cleared`, `JunkClear`
  (`KINDS`, `agentArg`, `retentionDays`, `plan`, `apply`, `running` = live session or `hostRunning`, `run`/`runIn`,
  `summed`, `toText`), `JunkClearCheck` (`protected`, `lockfileUnder`, `holdsLockfile`, `recheck`, `remove` keeping a
  cache's top folder and tag). `rtok.sys`: `removeTree` (`remove_dir_all` with errors), `moveToTrash` (NSFileManager,
  the `trash` crate's macOS method).
- `stale-worktrees` items are refused at apply ("worktree removal is not ported yet") until the worktree port brings
  junk_worktrees' `Remover`.
- Tests: JunkClearTest 5, JunkClearApplyTest 4.
- Stop point: next the `agents junk list|report|clear` CLI with the remaining junk.rs tests; then `--project`, the full
  restart.rs, worktree, junk_worktrees + `agent_worktrees`, the rest of phase 10.

## Session: agents — junk 8: `rtok agents junk list|clear` (cli/agents.rs JunkCmd, bytes.rs, tests/agents_junk.rs)
- `rtok.cli`: `JUNK` (`list` with `--json/--bytes/--all/--session-days` and the breakdown flags `--items/--sort/
  --min-size`; `clear` with `--yes/--dry-run/--stat/--json/--agent/--kind/--include/--older-than/--session-days/
  --trash`), clap-style usage errors (exit 2). T182's run without a filter, `JunkClear.run` with one or `--trash`.
- `rtok.Bytes.parse` (bytes.rs), `Cleared.json`/`Planned.json`, `JunkOwn.json`; `:rtok` uses `:humantime`.
- Fix: the ktoml adapter read `[[a.b.c]]` as arrays at `a` and `b` too (`[[agents.junk.extra]]` failed to load).
- Tests: BytesTest 1, KtomlReaderTest +1, AgentsJunkE2eSpec 10 (tests/agents_junk.rs without the every-host folder
  test, Linux `--trash`, stale worktrees and doctor).
- Stop point: next the remaining junk.rs unit tests (archive retention, host rows, VS Code CachedData, shared folders,
  `--all`, scan limit, unreadable folder, caches, shared tagged cache, temp/build/staging, sessions/index); then
  `--project`, the full restart.rs, worktree, junk_worktrees + `agent_worktrees`, the rest of phase 10.

## Session: agents — junk 9: the remaining junk.rs report tests
- Tests: JunkReportTest 7 (archive retention, host rows and the documented mark, VS Code CachedData, a shared folder,
  `--all`, a scan past its limit, an unreadable folder), JunkReportKindsTest 4 (caches under their agent, a shared
  tagged cache and a symlinked one, temp/build/staging against locks and swap, sessions and index classes). The
  hard-link case of `disk_usage` is rtok-sys DiskUsageTest. Every junk*.rs test is now ported except what needs the
  worktree module (junk_worktrees.rs, `stale-worktrees`).
- Stop point: next `--project`, the full restart.rs, worktree, junk_worktrees + `agent_worktrees`, the rest of phase 10.

## Session: agents — `--project` (post_create.rs, T289.3)
- `rtok.mcp.config.JsoncItems` + `JsoncScan` (jsonc.rs `Seg`, `locate`, `remove_at`, `is_empty_at`, `push_item`,
  `pull_items` and the span scanners): add or drop one list element of a JSONC file, every other byte kept.
- `rtok.agents.PostCreate` (+ `ProjectFile`, `PostCreateKilo`): Cursor `.cursor/worktrees.json` `setup-worktree*`
  lists, Devin/Windsurf `hooks.post_setup_worktree` in the file the host reads, Kilo's marked block after the shebang;
  no backup folder. `AgentsCommand.installProject` + `--project` on `agents install|setup|uninstall|remove`.
- Tests: JsoncItemsTest 4 (the jsonc.rs push/pull tests), PostCreateTest 11 (tests/agents_post_create.rs, run
  in-process instead of through the binary).
- Stop point: next the full restart.rs, worktree (incl. `rtok worktree adopt`), junk_worktrees + `agent_worktrees`,
  the rest of phase 10.

## Session: agents — the full restart.rs (T141, T434, T629)
- `Procs.quit`/`open` (`RealProcs`: `osascript … to quit`, `open -a`), `runOk` + `ProcError`, `RestartFlow.run` /
  `withRestart` (plan every host first, quit, wait up to 30 s, write, always reopen, warnings to stderr and the log).
  `AgentsRun.Env.procs`; `agents install|uninstall|update` honour `--no-restart`. CLI specs and CommandTest pass
  `--no-restart`/`noRestart` so a test never quits a real app. Windows `image_name`/`tasklist` not ported (macOS only).
- Tests: RestartRunTest 13 (the with_restart and run_ok tests).
- Stop point: next worktree (incl. `rtok worktree adopt`), junk_worktrees + `agent_worktrees`, the rest of phase 10.
## Session: worktree 1 — git.rs and the mod.rs core
- `rtok.worktree.Git` (all of git.rs: list, dirty/ignored, default base, squash-aware `isMerged` with the
  patch-equivalence fallback, own commits, lock/unlock/`unlockEnded`, move/remove/prune, branches, fetch,
  `pendingPaths`, commit/push, `addLocked`; `GitError` carries git's message). `WorktreeRecord.owner/heldAgainst/
  claimableBy`, `Owner` (v1/v2 lock reasons), `State`, `Facts`, `Entry`, `Worktrees.classify/deepest/checkoutOf/
  lockedBy/parMap/inventory/inventoryWith`, `DISABLED`. parMap runs on Dispatchers.Default (Rust: scoped threads).
- Tests: WorktreesTest 9 (mod.rs 6 incl. the rstest tables as loops, git.rs 2, locked_by 1).
- Stop point: next worktree list.rs, ops.rs, remove.rs, gc.rs, claim/adopt, the `rtok worktree` CLI; then
  junk_worktrees + `agent_worktrees`, the rest of phase 10.
## Session: worktree 2 — list.rs and origin.rs
- `rtok.worktree.Origin` (origin.rs: pools, `evicts`, `hosts`, `of`/`ofWith` with `$CODEX_HOME`). CacheUsage.kt gains
  `usageSources` (T936) and `newestUntil` (T948, stops at the first file that is enough). `WorktreeList` (list.rs:
  `Row`/`Bound`/`Seen` with Rust's JSON names, `orphans`, `bind`, `attribute`, `rows`/`rowsSized`,
  `attributeWithStore`, `toTable`).
- Tests: OriginTest 3, WorktreeListTest 6 (the list.rs tests not already in CleanTest).
- Stop point: next ops.rs, remove.rs, gc.rs, claim/adopt, the `rtok worktree` CLI; then junk_worktrees +
  `agent_worktrees`, the rest of phase 10.
## Session: worktree 3 — claim.rs (without `add`) (#139)
- `rtok.worktree.Claim` (`Held`, `heldAgainst` with T527's stale holder, `caller` with T973's provider/model refusal,
  `owner`, `run`/`bind`/`bindUnattended`, `staleLock` = gc.rs `stale_lock`), `ClaimStore` (`remember` with T934's
  retries, `release`, `completePending`, `registerProject`), `Adopted`, `WorktreeException`. `add`/`add_with` move to
  the add.rs PR. Test helper `holdWriteLock` (store `hold_write_lock`, Immediate).
- Tests: ClaimTest 8 (claim.rs tests 5 + lock_tests 3).
- Stop point: next remove.rs, gc.rs, ops.rs, the `rtok worktree` CLI (list/claim/adopt/remove/gc); then add.rs,
  junk_worktrees + `agent_worktrees`, the rest of phase 10.
## Session: worktree 4 — remove.rs, gc.rs, removal_preview.rs (#141)
- `rtok.RemovalPreview` (removal_preview.rs `size_of`, `render`; never painted). `WorktreeRemove` (`detach`, `run`/
  `preview`, `forAgent`/`previewForAgent`, `isTask` with ASCII-only folding), `Caller`, `Removed`. `Gc` (`decide` with
  T418/T453/T656/T949/T950, `judge`, `run`, `toTable`, `STALE_LOCK_DEFAULT`), `GcPolicy` (gc.rs `Policy`; `now` in
  nanoseconds), `Verdict`, `Outcome`, `Judged`. gc's progress bar is not ported (non-terminal output).
- Tests: RemoveTest 3 (remove.rs 1 table + removal_preview.rs 2), GcTest 6 (the gc.rs rstest tables as loops).
- Stop point: next ops.rs and the `rtok worktree` CLI (list/claim/adopt/remove/gc); then add.rs, whoami, finish,
  clean run, migrate, junk_worktrees + `agent_worktrees`, the rest of phase 10.
## Session: worktree 5 — ops.rs and the `rtok worktree` CLI (list, claim, adopt, remove, gc) (#144)
- `rtok.worktree.Ops` (ops.rs `claim`, `adopt`, `remove`, `list`, `live_ids`, `ended_ids`, `pending_paths`; the store
  is opened per call and closed after, the scan runs before it). `cli/WorktreeCmd.kt` (`rtok worktree claim|adopt|
  remove|list|gc`, the T410 `[worktree] enabled` gate, serde's pretty JSON: two-space indent, nulls kept, `pending`
  and a real removal's `bytes`/`files` omitted). add, finish, whoami, clean and migrate land with their modules.
- Tests: WorktreeE2eSpec 2 (tests/worktree.rs `list_splits_cache_from_source_and_reports_an_orphan`,
  `gc_removes_only_finished_worktrees_and_never_opens_a_foreign_lock`).
- Stop point: next add.rs (+ `rtok worktree add`, claim `add`/`add_with`, seed), whoami, finish, clean run, migrate
  and the remaining tests/worktree.rs cases; then junk_worktrees + `agent_worktrees`, the rest of phase 10.
## Session: junk 8 — junk_worktrees.rs and agent_worktrees
- `rtok.agents.JunkWorktrees` (junk_worktrees.rs: `KIND`, `worktrees` over `Ops.list` with the host from the bound agent,
  the session or the T289 pool, `policy` = gc's with `stale_worktree_days` and no "ours" lock, `items` with gc's
  wording per verdict plus orphans, `Remover` judging once per `clear` run and removing through
  `WorktreeRemove.detach`), junk_kinds.rs `agent_worktrees`/`in_hosts`. `Junk.reportInRepo` now fills `worktrees`
  and `staleWorktrees` as Rust's `report_in_repo`; `JunkClear.apply` removes `stale-worktrees` (refuses `--trash`:
  git unregisters a worktree) instead of skipping them as not ported.
- Tests: JunkWorktreesTest 1 (junk_kinds.rs agent worktree test), AgentsJunkE2eSpec +1 (tests/agents_junk.rs
  `stale_worktrees_are_what_gc_removes_and_clear_removes_only_those`; fakes only Claude Code, Rust fakes every host).
- Stop point: next the rest of phase 10: doctor, model, report, otel, tasks, completions, bench, batch, demon,
  last_paths, repo_map, hooks dispatcher tests; worktree add/finish/whoami/clean/migrate with their modules.
## Session: batch — batch.rs and `rtok batch`
- `rtok.BatchApi`/`BatchProvider` (batch.rs: submit/status/fetch through the proxy over the engine-neutral
  `rtok.http` client, `validate`, `checkId`, reqwest's `StatusCode` display for the error line), `cli/BatchCmd.kt`
  (`rtok batch submit|status|fetch`, `--provider`, `--url`). rtok-sys `createNewBytes` (`create_new`). A fetch writes
  the whole body once it has arrived (Rust streams and removes a torn file), so no partial file is left either way.
- Tests: BatchTest 4 (batch.rs). tests/batch_cli.rs (3, through a real proxy against httpmock upstreams) waits for
  the proxy port.
- Stop point: next doctor, model, report, otel, tasks, completions, bench, demon, last_paths, repo_map, hooks
  dispatcher tests.
## Session: graph repo map — mod.rs `repo_map` (SessionStart)
- `rtok.plugins.graph.RepoMap` (mod.rs `repo_map`: off at `map_tokens = 0`, `map_rank = "pagerank"` through
  `Rank.map` seeded by `last_paths` after a compact, the `symbol_top_refs` fallback trimmed line by line to the
  cap, priority 1); `GraphPlugin.sessionStart` wires it.
- Tests: RepoMapTest 3 (mod.rs `repo_map_off_by_default_and_empty_index`,
  `repo_map_pagerank_lists_files_and_falls_back_without_a_graph`, `repo_map_ranked_byte_stable_and_trimmed`).
- Stop point: next doctor, model, report, otel, tasks, completions, bench, demon, hooks dispatcher tests.
## Session: doctor — hooks check (doctor/probe.rs, hooks.rs, dupes.rs)
- `rtok.doctor.Probes` (probe.rs `Fs`/`Env`/`Which` and the real ones; `Writer` lands with `--fix`), `Problem`,
  `HookCommand` (hooks.rs command half: POSIX and Windows word rules, `expand`, `target`, `normalize`, `classify`,
  PATHEXT lookup, unmounted `/Volumes`), `Hooks` (settings sources and ranks, other hosts' JSON/TOML hook files,
  enabled Claude plugins, host plugin links, `check`/`check_for`/`check_with_plugins`, `host_id`, `render`, doctor.rs
  `plugin_install_paths`), `Dupes` (dupes.rs `find`, `render`, `render_mcp`). Object keys are walked sorted, as
  serde_json's map does.
- Tests: HooksTest 43 (hooks.rs 44 unix + Windows-rule tests; the source-scanning `only_the_probe_module_touches_the_machine`
  guard is not ported: it reads the crate's own files).
- Stop point: next doctor checklist.rs, fix.rs, mcp_dupes.rs, mcp_fix.rs, web.rs, then doctor.rs + `rtok doctor`.
## Session: doctor — duplicate MCP entries (doctor/mcp_dupes.rs)
- `rtok.doctor.McpDupes` (`Scope` keep/wins order, surfaces over every host plus Claude's project `.mcp.json`, local
  `projects.<cwd>` and enabled plugins' `.mcp.json`, Gemini's extension manifest; normalized launch with `PATH` +
  symlinks, `npx/bunx/pnpx/uvx` package and version, URL normal form; shadowed names, copies, version conflicts and
  rtok's own entries), `Loc` + `locations` for `--fix`. BTreeMap groups are walked in key order as in Rust.
- Tests: McpDupesTest 19 (mcp_dupes.rs 18 + URL normal form).
- Stop point: next doctor mcp_fix.rs, checklist.rs, fix.rs, web.rs, then doctor.rs + `rtok doctor`.
## Session: doctor — mcp_fix.rs, checklist.rs, fix.rs part 1
- `rtok.doctor.McpFix` (`remove`: JSONC member removal through `JsoncItems.removeAt`, a TOML server table or inline
  key through `:toml-aot`, then the servers and the rest of the file are parsed back and compared).
- `rtok.doctor.Checklist` (`Prompt`, `Choice`, `copyOf`, `keepCopy`, `sharedIn`, `defaults`, `items`, `run`) and
  `rtok.doctor.Fix` part 1 (`KINDS`, `isRtokOwn`, `isOwn`, `parsePath`, `wanted`, `removable`).
- Tests: McpFixTest 3 (mcp_fix.rs 1 + JSONC and TOML removals), ChecklistTest 4 (fix.rs `is_rtok_own`, `parse_path` + scripted checklist runs; the
  file-writing fix.rs tests land with the rest of fix.rs).
- Stop point: next fix.rs rest (`Writer` backup + atomic swap, `fix_found`, `fix_file`, `render`, `interactive`), web.rs,
  then doctor.rs + `rtok doctor`.
## Session: doctor — fix.rs rest (backup + atomic write, per-file apply, report, interactive)
- `rtok.doctor.Writer` + `RealWriter` (probe.rs: `rtok-agent-sdk` `backup` then `writeAtomic`), `Outcome`, `FileFix`,
  `FixReport` (`exitCode`), `FixOpts`; `Fix.removeEntries`, `hookList`, `edit`/`editHooks` (the re-parse check),
  `fixBroken`, `fixFor`, `candidates`, `fixFound` (broken copies keep the extras, refusals), `fixFile` (re-read before
  the write, backup, swap), `refusals`, `diffs`, `label`, `render` (`--stat` aware), `run`, `interactive`.
- Tests: FixTest 20 (fix.rs file-writing tests; the two proptests run as seeded 64-case loops, no property-test
  dependency). `FixMachine` is the shared in-memory machine (fix.rs `tests::Machine`) for the doctor web tests.
- Stop point: next doctor web.rs, then doctor.rs + `rtok doctor` (--json, --fix).
## Session: doctor — web.rs (the web page's --fix plan and apply)
- `rtok.doctor.DoctorWeb` (`plan`, `apply` with the preview token, `prepare` keep swaps + toggles, `defaultOpts`,
  `planHere`, `applyHere`) and the serializable `Ref`, `Selection`, `FixItem`, `FixPlan`, `Fixed` (snake_case JSON
  names as in Rust). The web socket's `doctor` call can now be wired to it by the web worker.
- Tests: DoctorWebTest 8 (web.rs 8).
- Stop point: next doctor.rs + `rtok doctor` (--json, --fix).
## Session: doctor.rs part 1 — Report, renderers, advice, skills and instruction audits
- `rtok.doctor.Report` (+ `AgentModules`, `ServerInfo`, `Instructions`, `InstructionRow`, `ReadShare`, `ToolSearch`,
  `ToolSearchState`) with `toText`/`toConsole`/`toCompactText`; `Advice` (`resolveToolSearch`, `anthropicBase`,
  `toolsRewriteAdvice`, `alwaysLoadAdvice`, `overlapLines`, `hookClientAdvice`, `countHooks`/`hooksObject`,
  `pluginInstallPaths`); `SkillsAuditProbe` (`SKILL_ROOTS`, `auditFrom`, per-host duplicates, `repeatRows`, plugin
  skill dirs) and `InstructionAudit` (injectors, symlink-aware sources, linked-worktree project files, duplicates).
- Tests: DoctorReportTest 23 (doctor.rs render/advice/audit tests; the hub-skill and ketch-manifest tests are not
  ported: no `skills/` or `ketch.toml` in this repo; `hook_client_link_silences_the_advice` waits for
  `ensure_hook_client_link`).
- Stop point: next doctor.rs part 2 (`page`, MCP server probe, proxy chain, read-share) + `rtok doctor` CLI.
## Session: doctor.rs part 2 — page() and `rtok doctor`
- `rtok.doctor.Page` (`page`: settings + plugin-carried hooks, MCP servers spawned through `Roundtrip` for tool counts
  and description tokens, tool search, Anthropic and OpenAI proxy chains over `/health`, advice, overlaps, agents
  module rows, hook and MCP checks, graph alerts and health, config notes), `McpServer`, `DoctorJson` (the serde
  `--json` shape with its skips), `Layers.doctorFlags`, and `rtok doctor` (`--instructions`, `--json`, `--agent`,
  `--fix` with `--yes`, `--dry-run`, `--stat`, `--only`, the checklist on a terminal).
- Deviations: read-share and skill usage counts report "no data" until measure/transcript_cache.rs is ported (so the
  read-share fraction test is not ported yet); no progress bar while MCP servers are probed; the proxy probe goes
  through the `:http` client instead of a hand-rolled socket.
- Tests: DoctorPageTest 12.
- Stop point: next tasks, model, report, otel, completions, bench, demon, hooks dispatcher tests; worktree
  add/finish/whoami/clean/migrate; transcript_cache (unblocks doctor read-share).
## Session: measure/transcript_cache.rs (+ doctor read-share and skill usage)
- `rtok.measure.TranscriptCache` (`FileAgg` with the Rust JSON field names, `scan` through the process cache seeded
  from `transcripts-cache.json` beside the store, `scanWith`, `aggregate`, `estTokens`, `load`, atomic `save`);
  `Parsed` (jsonl.rs) gains `toolUses`, `toolResults`, `injected` and `compactions`.
- `rtok.doctor.Usage` (`readShare`, `skillUsage`): doctor's read-share line and the skills audit's call counts,
  never-invoked flags and repeat loads now come from the transcripts.
- Tests: TranscriptCacheTest 2, DoctorPageTest +1 (read-share fraction).
- Stop point: next tasks, model, report, otel, completions, bench, demon, hooks dispatcher tests; worktree verbs.
## Session: tasks part 1 — domain, labels, adapter interface, ready queue
- `rtok.tasks`: `Tasks.kt` (tasks/mod.rs: `Status` with host words, `Task`/`NewTask`/`ExternalRef` in the serde JSON
  shape with its skips, `ReadyItem`/`ClaimOutcome` flattened JSON, `ClaimConflict`, `defaultPrefix`/`resolvePrefix`),
  `Meta.kt` (meta.rs managed labels), `Adapter.kt` (adapter.rs `TaskAdapter`, `Filter`, `Taken`, `Stray`,
  `setStatus` parent guard), `Ready.kt` (ready.rs `select`, `holderIsStale`, `activeBlocker`, `findCycles`).
- Tests: TasksTest 6 + ReadyTest 8 (TaskId's mod.rs tests were already covered in :rtok-store).
- Stop point: next tasks part 2: disk.rs + plan_dir.rs (disk adapter), then run.rs/sync.rs/ops.rs and `rtok task`;
  github/gitlab/export adapters after.
## Session: tasks part 2 — disk adapter (file and folder layouts)
- `rtok.tasks`: `DiskAdapter.kt` (disk.rs adapter: plan + `done/`, skip broken files but keep their id, front-matter id
  must match the name), `DiskFile.kt` (render/parse of the front matter on `:yaml-aot`, serde field order and skips),
  `DiskName.kt` (`<id> - <slug>.md`, slug), `ClaimLockFile.kt` (`.claim.lock` flock polled 50×20 ms, never unlinked;
  once-per-process skip warnings), `PlanDir.kt` (plan_dir.rs folder layout: three-line front-matter rewrite, `done.md`
  reports, refuses blockers/conflicts/parallel).
- Tests: DiskTest 9 + ClaimLockTest 3 + PlanDirTest 8; `PlanRules.kt` ports tests/common/plan_rules.rs as a test helper.
- Deviations: skip warnings go to `taskWarningSink` (silent until the CLI wires it) instead of `log::warn`; the
  killed-holder lock test uses a `perl` flock child instead of re-running the test binary; a status accepted by
  `Status.parse` (host words) also parses from front matter.
- Stop point: next run.rs/sync.rs/ops.rs and `rtok task`; github/gitlab/export adapters after.
## Session: tasks part 3 — run.rs (project, claims, edges, texts)
- `rtok.tasks`: `TaskProject.kt` (run.rs `Project`: open with the disk adapter picker, create with counter seeding and
  Taken retries, seed, status with closing report, show, ready/next; `closeIfActive`), `TaskClaims.kt` (claim,
  release, refusal order), `TaskEdges.kt` (block with cycle check, symmetric conflicts, parallel, priority),
  `TaskText.kt` (`initTasks` on `:toml-aot` keeping other lines, `requireAgent`, `taskFilter`, ready/list/show texts).
- Tests: TaskProjectTest 7 (the race test runs two Workers on one in-memory store).
- Deviations: `Project` is `TaskProject`; the github/gitlab arms of `open` fail with "not ported yet" until those
  adapters land, so run.rs's GitLab origin test waits for them.
- Stop point: next sync.rs, ops.rs and the `rtok task` command; then github/gitlab/export adapters.
## Session: tasks part 4 — sync.rs, ops.rs, `rtok task`
- `rtok.tasks`: `TaskSync.kt` (sync.rs: counters raised per parent in BTreeMap order, above-counter ids, duplicates,
  unlabelled strays, report JSON and text), `TaskOps.kt` (ops.rs store-backed operations).
- `rtok.cli.TaskCmd` (cli/task.rs): create/list/show/status/next/ready/claim/release/dep/priority/conflict/parallel/
  sync/init, registered between worktree and batch.
- Tests: TaskSyncTest 4; `:rtok-e2e` TaskE2eSpec 5 (tests/task_cli.rs CLI halves; agents registered straight in the
  sandbox store, so `:rtok-e2e` test now depends on `:rtok-store` and `:db-room`).
- Deviations: `rtok task export` and the MCP halves of task_cli.rs wait for the export adapters and task MCP tools;
  sync.rs's GitHub read-only test waits for the github adapter.
- Stop point: next github/gitlab adapters (remote.rs, github.rs, github_project.rs, gitlab.rs), then export
  (export.rs, export/github.rs, export/linear.rs) and `rtok task export`; task MCP tools.
## Session: tasks part 5 — github adapter (remote.rs, github.rs, github_project.rs)
- `:http`: `HttpClient.request(method, req)` and `HttpResponse.headers`/`header()`; `KtorHttp` implements both.
- `rtok.tasks`: `Remote.kt` (labels/titles, token lookup env then `gh auth token`, `RemoteHttp` with write pacing,
  rate-limit waits, `Link: rel="next"` paging kept on the API host), `Github.kt` (`GithubAdapter`, `githubRepo`,
  shared `graphql`), `GithubProject.kt` (Projects v2 Status sync, failures go to `taskWarningSink`).
  `TaskProject.open` now builds the github adapter (Ktor client, 30 s timeout).
- Tests: KtorHttpTest +1, RemoteTest 6, GithubTest 9, GithubProjectTest 5. A scripted `FakeHttp` (`rtok.http.HttpClient`)
  replaces httpmock; no new dependency.
- Deviations: sub-issue link and project failures are warnings through `taskWarningSink` (still silent until the
  CLI wires it); run.rs/sync.rs GitHub tests still wait (sync read-only test next with gitlab).
- Stop point: next gitlab.rs, then export (export.rs, export/github.rs, export/linear.rs), `rtok task export`, task MCP
  tools, wire `taskWarningSink` to the CLI.
## Session: tasks part 6 — gitlab adapter (gitlab.rs)
- `rtok.tasks.Gitlab.kt`: `GitlabAdapter` (REST v4, `status::*` scoped labels, `relates_to` parent link as a warning
  on failure, minimal label diff on save), `gitlabInstance` (plain https base, own parser instead of `url::Url`),
  `gitlabApiBase`, `gitlabProject` (config, numeric id or origin under the instance path). `TaskProject.open` builds it
  (token from GITLAB_TOKEN/GITLAB_ACCESS_TOKEN/GL_TOKEN, else `glab config get token --host`); `remoteClient()` shared.
- Tests: GitlabTest 11 (gitlab.rs 10 + run.rs's gitlab open test).
- Stop point: next export (export.rs, export/github.rs, export/linear.rs), `rtok task export`, task MCP tools, wire
  `taskWarningSink` to the CLI; sync.rs's GitHub read-only test.

## Session: tasks part 7 — export (export.rs, export/github.rs, export/linear.rs), `rtok task export`, warnings
- `rtok.tasks.Export.kt` (diff, drift, run, render, `openExporter`), `GithubExporter.kt` (drives `GithubAdapter`,
  issues only), `LinearExporter.kt` (GraphQL: team states by type, label ids created on demand, paging, archived
  issues kept). `graphql()` takes an endpoint path and message prefix so Linear reuses it. `TaskOps.export` (disk
  adapter only) and `rtok task export <github|linear> [--dry-run] [--json]` (unknown backend: usage error, exit 2).
- `StderrLog.kt`: Rust's `log` + env_logger on stderr (RUST_LOG directives, default line format). Every `rtok task`
  action now points `taskWarningSink` at it (target `rtok::tasks`, level warn), like `log::warn!`.
- Tests: ExportTest 6, GithubExporterTest 5, LinearExporterTest 7 (all fake HTTP), StderrLogTest 3, TaskE2eSpec +1
  (task_cli.rs export test, plus the Linear team check). FakeHttp gained `onIf` (httpmock `is_true`).
- Stop point: next task MCP tools (MCP halves of task_cli.rs), sync.rs's GitHub read-only test; then model, report,
  otel, completions, bench, demon, hooks dispatcher tests, worktree add/finish/whoami/clean/migrate, proxy batch tests.
## Ownership: stats + skills (web/dashboard worker)
- The web/dashboard worker owns `measure/stats.rs` (and what `rtok stats` needs: `cli/stats.rs`, `measure/baseline.rs`,
  `measure/cache.rs`, `measure/skills_listing.rs`), the snapshot's `stats`/`skills` (model/mod.rs `stats_skills`,
  `skills_from`, `stats_page_text`), the Stats and Skills pages and tui tabs. The main-port worker skips these.
## Session: stats part 1 — measure deps (jsonl stats fields, image.rs, dictionary.rs, subagents.rs)
- `Jsonl.kt`: assistant texts, thinking blocks, image blocks, `firstUserText`, `subagentStartContext`; byte sizes are
  UTF-8 lengths (`Sizes.kt` `utf8Len`, `estTokens`, `pct`, `toolPath`, `samePath`).
- `ImageCost.kt` (image.rs), `Dictionary.kt` (dictionary.rs), `Subagents.kt` (full subagents.rs: `SubagentScan`,
  `SubagentParent`, brief split).
- Tests: JsonlTest 7, ImageCostTest 2, DictionaryTest 5, SubagentsTest 2.
- Next: stats.rs (Report, fold, table, store attaches, cost), `rtok stats`, baseline.rs, cache.rs, then the snapshot.
## Session: stats part 2 — measure/stats.rs
- `rtok.measure.stats`: `Report` and its rows (`Report.kt`, JSON in Rust field order with serde's skips, `SortedMap` for
  `BTreeMap`), `collect`/`foldSession` (`Collect.kt`), the folds (`Folds.kt`, `ReadFolds.kt`), `toTable`
  (`Table.kt`), the store attaches, `laneOfKind`, `codexRow` (codex.rs `collect`) and `--price` (`Attach.kt`).
- `SerdeJson.kt`: serde_json pretty/compact output with ryu floats, and `fixed` for `{:.N}`.
- Tests: all 35 stats.rs tests except the `parse_since` trio (already ported with `rtok.config.parseSince`):
  StatsCollectTest 10, StatsFoldTest 15, StatsStoreTest 7.
- Next: `rtok stats` CLI + baseline.rs + cache.rs, then the snapshot `stats`/`skills`, pages and TUI tabs.

## Session: tasks part 8 — task MCP tools (mcp/tasks.rs), MCP halves of task_cli.rs, sync.rs GitHub test
- `rtok.mcp.TaskTools` (mcp/tasks.rs): the 12 `task_*` tools, listed after the agent tools and dispatched before the
  rooted tools; config re-read for the cwd on every call; JSON identical to `rtok task … --json`.
- `rtok mcp --call` now matches cli/mcp.rs: arguments in `--json` (was `--args`), text on stdout without a newline,
  a tool failure printed on stdout with exit 1.
- Tests: ServerTest +1 (task tools listed after the agent tools), TaskSyncTest +1 (`github_sync_only_reads`, FakeHttp),
  TaskE2eSpec +1 (`mcp_tools_answer_like_the_cli`) and the MCP half of the markers test.
- Deviations: `the_task_skill_names_exactly_these_tools` not ported — the Kotlin repo ships no `skills/` yet.
- Stop point: next model, report, otel, completions, bench, demon, hooks dispatcher tests, worktree
  add/finish/whoami/clean/migrate, proxy batch tests, macOS/Linux split.

## Session: otel part 1 — otlp.rs, map.rs, metrics.rs
- `rtok.otel.Otlp` (OTLP/HTTP JSON bodies for traces, logs and cumulative sums; trace/span ids from SHA-256),
  `rtok.otel.Map` (session root span, call spans by surface hook/mcp/proxy, content cap on UTF-8 boundaries with the
  archive id, log records), `rtok.otel.Metrics` (`rtok.tokens`, `rtok.tokens.saved`, `rtok.calls`).
- Tests: OtlpTest 3, MapTest 4 (all of otlp.rs and map.rs).
- Stop point: next otel export.rs (flush, coalesced flush, status, HTTP via fake server), `rtok otel flush|status`,
  then model, report, completions, bench, demon, hooks dispatcher tests, worktree add/finish/whoami/clean/migrate,
  proxy batch tests, macOS/Linux split.
## Session: stats part 3 — `rtok stats` (cli/stats.rs), baseline.rs, cache.rs, snapshot stats/skills
- `rtok stats` (`cli/StatsCmd.kt`): `--since/--json/--plugin/--save-baseline/--compare/--calibrate/--cache/--price`
  merged into `[stats]` like `stats_flags`; pages from `rtok.model.StatsPage` (`stats_report`, `plugin_stats`,
  `cache_health` of model/mod.rs).
- `measure/stats/Baseline.kt` (baseline.rs; the load reads back only what `compare` prints), `CacheHealth.kt` (cache.rs).
- Tests: CacheHealthTest 4 (cache.rs 3 + baseline.rs 1), StatsCliSpec 8 (tests/stats_price.rs 4, the stats checks of
  commands_e2e.rs/plugins_e2e.rs, `--since`, baselines, `--cache`, `--plugin archive`).
- Snapshot: `stats` (the `--price` table plus `--cache`'s, "reading transcripts…" until the first scan) and `skills`
  (`SkillsPage` header + rows) from `rtok.model.StatsSkillsPage` — one Background scan (300 s TTL, keyed on
  `transcripts_dir`+`since`) feeds both; the doctor report is read once for `doctor` and the skills listing.
- `measure/SkillsListing.kt` (skills_listing.rs). Tests: SkillsListingTest 2 (its unit test + the fixture count of
  tests/skill_listing.rs), StatsSkillsTest 3 (`skills_from`, `stats_skills_never_waits_on_the_transcript_scan`,
  snapshot keys).
- Deviation: tests/skill_listing.rs's proxy-capture half (axum + httpmock) is not ported; the measure is.
- Next: the Stats and Skills pages (Kotlin/JS) and TUI tabs.

## Ownership (2026-10-27)
- Web/dashboard worker (worktree `~/Grok/rtok-web-dashboard`): web Graph page gaps (docs/web.md "Audit"), then
  worktree add/finish/whoami/clean run/migrate with their modules, and the hooks dispatcher tests. The main-port
  worker skips these.
- Session (web/dashboard worker): `add.rs` (`Add.plan`/`landing`/`ensureIgnored`/`ensureRoom`/`run`, `Seams`, `Undo`),
  claim.rs `add`/`add_with`/`copy_symbol_index` (`AddClaim.kt`), `whoami.rs` (`Whoami.kt`), `rtok worktree add|whoami`,
  rtok-sys `freeBytes` (statvfs through the spawn cinterop). Tests: AddTest 6 (add.rs; Windows-only dropped), AddRunTest 5 own.
  Not yet: `seed.rs` (`Seed.fromSiblings` is a no-op until the copy-on-write clone lands) and `task_list::write_for`
  (the new worktree's task list id). Next: seed, task_list, finish, clean run, migrate, hooks dispatcher tests.
- Session (web/dashboard worker): `seed.rs` (`Seed.run`/`positions`/`freshest`/`fromSiblings`; `--no-seed` now skips a
  real seed) on rtok-sys `cloneTree`/`Cloned` (fs-kit `clone_tree`: APFS `clonefile`, Linux `FICLONE`, else a full
  copy; links kept, mtimes kept; C helper `rtok_clone_or_copy` in librtok_spawn.a), and `agents/task_list.rs`
  (`TaskList.writeFor`, called by `rtok worktree add`; the MCP `worktree_add` call lands with the MCP worktree tools).
  Tests: SeedTest 4, TaskListTest 5 (all Rust cases). Next: finish, clean run, migrate, hooks dispatcher tests.
## Session: otel part 2 — export.rs, `rtok otel flush|status`
- `rtok.otel.Export` (export.rs): flush per stream past the watermarks (traces = ended sessions + calls, stopping in
  front of an in-flight call; logs; metrics sums every flush), marks only on 2xx, a 404 stream skipped and not logged,
  errors joined into one report line and one `logs` row; the cross-process flush lock (`rtok.otel-flush.lock`, blocking
  flock via new rtok-sys `lockExclusive`) and the T143 queued slot (`flushCoalesced`, `queuedLockHeld`); `status`,
  `statusJson` (model `otel_status`); `spawnChild` (detached `rtok otel flush --coalesce`) now wired as
  `HookLinks.spawnOtelExport`; `FlushTrace` (`RTOK_OTEL_FLUSH_TRACE`). HTTP goes through `rtok.http.HttpClientFactory`
  (KtorHttp by default), timeout `otel.flush_secs`.
- `rtok otel flush [--coalesce]`, `rtok otel status [--json]` (`cli/OtelCmd.kt`).
- Tests: ExportTest 20 (export.rs unit tests minus the 2 ticker ones, tests/otel.rs exporter tests against the scripted
  `FakeHttp` collector, refused port and a never-answering loopback socket via the real client, coalesce back-off),
  OtelCliSpec 2.
- Deviations: `spawn_ticker` (periodic flush in proxy/mcp) not ported yet; `--coalesce` is visible in help (the CLI
  model has no hidden flags); no "flushing telemetry…" spinner; `telemetry.sdk.language` = `kotlin`; the T143/T161
  multi-process hook tests (spawned children, `.spawned` marker) not ported — `spawnDetached` returns no pid.
- Stop point: next spawn_ticker wiring, model, report, completions, bench, demon, proxy batch tests, macOS/Linux split.
  (worktree add/finish/whoami/clean/migrate and the hooks dispatcher tests moved to the second worker.)
## Session: otel ticker and retention — export.rs `spawn_ticker`, store/mod.rs `spawn_retention`
- `rtok.otel.Ticker.spawn` (export.rs `spawn_ticker`: no endpoint → nothing starts and no store opens; otherwise a
  worker with its own runtime flushes after each full `flush_secs` period, no stop handle), `rtok.Retention`
  (`spawn_retention`: housekeeping on its own worker, warnings to stderr and the file log as `rtok <surface>: …`).
  Both wired into `rtok mcp` (stdio) and `rtok mcp --http`; the proxy wires them when it is ported.
- Tests: TickerTest 2 (export.rs ticker tests), RetentionTest 1.
- Stop point: next model, report, completions, bench, demon, proxy batch tests, macOS/Linux split.
- Session (web/dashboard worker): web Graph scene nodes are clickable (click or Enter): the same `open` as the drill
  node list (a file toggles, a symbol is focused, in its own project when foreign); `data-node` delegation on the SVG.

## Session: proxy chunk 1 — route, gate, noise, chat ToolResults
- `rtok.proxy.Route` (route.rs: lane model routing, closed without a named bench), `Gate` (gate.rs: per-lane
  in-flight cap on a coroutines `Semaphore`, queue counter released on cancel; `Slot.release()` stands in for
  dropping the permit), `Noise` (noise.rs: terminal noise out of tool results and user text, fail-open, same bytes
  when clean), `OpenAiChatWire` (openai_chat.rs ToolResults + live blobs).
- Tests: RouteTest 5, GateTest 3, NoiseTest 4 (all of route.rs, gate.rs, noise.rs).
- Proxy chunk plan: 2 = wire.rs rest (Usage, Wire trait, SSE usage) + Anthropic/Chat/Responses/Gemini usage and
  prepare_request; 3 = lane.rs, live.rs, flex.rs; 4 = tools_rewrite.rs, batch_results.rs; 5 = semantic_cache.rs;
  6 = mod.rs server (Ktor) + cli.rs + otel ticker/retention wiring; 7 = tests/batch_cli.rs (3).
- Stop point: next proxy chunk 2.

## Session: proxy chunk 2 — wire usage and the four dialects
- `rtok.proxy.Wire` (wire.rs `Wire` trait: matches, provider, api, session id, model, usage from body/SSE,
  `prepareRequest` returning the new body or null, saturating `providerTotal`), `Wires` (slugs, `forPath`),
  `Usage`/`UsageFields`/`findUsage`/`intField`, `Responses.usage`/`serviceTier` (lossy SSE decode, T584),
  `Upstream.join` (own URL split, dot segments refused), `SkillRefs` (`collect_skill_refs`).
- Wires: `AnthropicWire` (+ usage, skill refs, `applyContextEdits`; file renamed from Anthropic.kt),
  `OpenAiChatWire` (+ usage, skill refs, `include_usage`), `OpenAiResponsesWire`, `GeminiWire` (new).
- Tests: WireTest 7, AnthropicUsageTest 4, ChatWireTest 3, ResponsesWireTest 4, GeminiWireTest 6 (all Rust tests of
  wire.rs and the four dialect files, Anthropic's tool-result test was already ported).
- Stop point: next proxy chunk 3 (lane.rs, live.rs, flex.rs).

## Session: proxy chunk 3 — lanes, live ring, Flex policy
- `rtok.lane.LaneKinds` (src/lane.rs: lane `calls.kind`s, `x-rtok-lane`, `isApiRequest`, `laneOfKind`); stats
  `Attach`, otel `Export` and compress `Compressor` now use it instead of their own copies.
- `rtok.proxy.Lane` (lane.rs: classify by path, `/lane/<name>/` prefix or header; policy, route, passesThrough, kind).
- `LiveRing` + `Live` (live.rs): lock-free copy-on-write newest-first ring capped at 120, plain-mode alerts. Tests use
  their own ring, so Rust's `test_lock` is not needed.
- `Flex` (flex.rs policy half): lane timeout, `Retry-After` delta seconds, `apply` (byte splice when absent),
  `Retry.next`/fallback. Deviation: `flex::send` (the retrying send loop) lands with the server chunk, which owns
  the HTTP client.
- Tests: LaneTest 10 (rstest cases as loops), LiveRingTest 2, FlexTest 10.
- Stop point: next proxy chunk 4 (tools_rewrite.rs, batch_results.rs).
## Session (worktree worker): finish.rs — `rtok worktree finish`
- `rtok.worktree.Finish` (finish.rs: `message_for`, `open_pr`, `for_agent`), `FinishOptions`, `Finished` (`to_text`,
  JSON as Rust: `committed`/`warnings` omitted when empty), `GhRunner` (the `gh` seam; `HOST` spawns gh in the worktree),
  `Ops.finish`, `rtok worktree finish [PATH] [-m] [--repo] [--no-auto-merge] [--agent] [--owner] [--json]`.
  rtok-sys `ProgressRunner.cwd` (posix_spawn addchdir).
- Tests: FinishTest 3 (the message_for cases; the tests/worktree.rs finish test against temp origin/clone and a scripted
  gh; the text form).
- Deviation: the finish test injects the gh seam instead of a fake `gh` first on `PATH`.
- Same PR: clean.rs rest (`Clean.run`/`toTable`, `CleanOutcome`; `rtok worktree clean [PATHS…] [--idle] [--yes]
  [--dry-run] [--stat] [--json]`), migrate.rs (`Migrate.plan`/`run`/`toTable`, `MigrateMe`, `MigrateHeld`,
  `MigrateOutcome`; `Ops.migrate`; `rtok worktree migrate [--yes] [--owner] [--json]`), mcp/worktrees.rs
  (`rtok.mcp.WorktreeTools`: `worktree_add` (+ `TaskList.writeFor`), `worktree_list`, `worktree_remove`,
  `worktree_finish`, `worktree_adopt`, `with_repo`, `ensure_callers_repo`; listed right after `whoami` as in mcp.rs).
- Tests: MigrateTest 3 (both tests/worktree.rs migrate tests + the table), WorktreeE2eSpec clean test (T152),
  AgentToolsTest: the listing order now with the worktree tools, worktree_add wrong-repo test (T924).
- Deviations: clean has no progress bar for several caches (no batch bar in rtok-sys yet).
- Stop point: next hooks dispatcher tests.

## Session: proxy chunk 4 — tools rewrite
- `rtok.proxy.ToolsRewriter` (tools_rewrite.rs `rewrite`: T59.5 description truncation, allow/deny, Delta with the
  original `tools[]` when changed; returns the new body since Kotlin JSON is immutable), `SchemaCompact` (T686
  first-sentence descriptions, restating `examples`, zero `default`s; keyword-only walk), `Sentences` (split,
  `truncateAtSentence`, token prefix).
- Fixture: tests/fixtures/mcp_catalog_github.json copied to rtok/src/commonTest/fixtures.
- Tests: ToolsRewriteTest 7, ToolsSchemaCompactTest 5 (all 12 Rust tests).
- Stop point: next proxy chunk 5 (batch_results.rs), then semantic_cache.rs.

## Session: proxy chunk 5 — batch results
- `rtok.proxy.BatchResults` (batch_results.rs: `source`, `record` with T583 per-location dedupe keys, JSONL `parse`
  split on bytes, Anthropic and OpenAI line shapes).
- Tests: BatchResultsTest 6 (5 Rust tests + `source`).
- Stop point: next semantic_cache.rs.

## Session: proxy chunk 6 — semantic cache
- `CachePrompt`/`CacheKey` (prompt building, caller identity, cache scope, eligibility, canonical and scope hashes),
  `CacheText` (canonical JSON, typed digests, block text), `ResponseCache` (direct + semantic tiers, TTL pruning on
  store, 2xx only, `measurement`, feature-hashed embedding, cosine; injectable `TimeSource` instead of Rust's
  rewritten `Instant`s in tests), `CacheAudit` (`audit_corpus`).
- Fixture: tests/fixtures/p9_semantic_cache_corpus copied to rtok/src/commonTest/fixtures.
- Deviation: the embedding tokenizer treats BMP letters/digits via `Char.isLetterOrDigit` (Rust `is_alphanumeric`)
  and every non-BMP code point as a symbol.
- Tests: CacheKeyTest 11, ResponseCacheTest 9 (all 19 Rust tests; rstest cases as loops).
- Stop point: next the proxy server (mod.rs) + cli.rs + Flex send loop + otel ticker/retention wiring.
