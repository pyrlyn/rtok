# rtok → Kotlin/Native port plan

Source: `apps/rtok` (Rust, ~200k lines incl. tests; `src/` 166k, crates 31k). Target: this repo.
Goal: behavior- and structure-preserving port; CLI flags, output formats, config (TOML) and
on-disk formats (SQLite ledger `rtok.db`, payload files) stay interchangeable with Rust rtok.
**Excluded:** TUI (`src/tui`, ratatui/crossterm UI, `rtok tui`) and web UI (`src/web`,
`crates/rtok-webui`, `rtok web`, `rtok dashboard`, `agents/junk_web.rs`).

## Structure mirror
| Rust crate / dir | Gradle module | Kotlin package |
|---|---|---|
| crates/rtok-plugin-sdk | `:rtok-plugin-sdk` | `rtok.plugin.sdk` |
| crates/rtok-config | `:rtok-config` | `rtok.config` |
| crates/rtok-log | `:rtok-log` | `rtok.log` |
| crates/rtok-hook | `:rtok-hook` | `rtok.hook` |
| crates/rtok-store | `:rtok-store` | `rtok.store` |
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
- [ ] Tests: port matching Rust unit + integration tests to kotlin.test; phase done only when they pass on the Mac (`./gradlew macosArm64Test`)
- [ ] Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) + README section
- [ ] migrations identical to Rust; ledger/call_log/archive/payload-by-id; retention + tests
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
| **total** | | **162** |

## Test-porting
Each Rust `#[cfg(test)]` module → `src/commonTest` in the matching module; `tests/*.rs` (138 files)
→ `:rtok` integration tests driving the built binary. Track count in PORTING.md.

## Risks / non-portable
- wasmi plugin host (`plugins/wasm.rs`): no Kotlin/Native wasm runtime; would need wasm3/wamr cinterop.
- tree-sitter: 13 grammars need cinterop + vendored C sources per target.
- rustls/webpki: replaced by platform TLS (Darwin/Curl engines) — behaviour differs.
- printpdf/resvg (report PDF/SVG): no native equivalent; report limited to text/JSON.
- Scope: ~200k lines; full parity is a multi-month effort (see PORTING.md for live status).
