# rtok-kotlin — phases 7–11

Companion to [port-plan.md](../port-plan.md) and [PORTING.md](../PORTING.md). Sequence set by Ivan
(26 Oct 2026). It replaces the provisional phase 7–11 headings in port-plan.md (7 proxy, 8 MCP,
9 agents, 10 graph/otel/worktree/task, 11 docs). None of that scope is dropped: proxy, MCP, agents,
otel, worktree, tasks and the other backends move into phase 10, next to the CLI commands that
use them. Graph moves into phase 8 because it is a plugin.

**This is a port.** Every phase mirrors the Rust layout one to one: one Kotlin package per
`src/<dir>`, one Gradle module per crate, the same file split and the same test names. Refactoring
is out of scope for phases 7–11 and comes after parity.

Rust numbers below come from `apps/rtok` (`find … -name '*.rs' | wc -l`; tests = `#[test]` +
`#[tokio::test]`, rstest cases not expanded). Kotlin state is from `main` at `3587576`.

Standing rules apply to every phase (port-plan.md):
- Tests: port the matching Rust unit and integration tests. A phase is done only when
  `./gradlew macosArm64Test`, `cliTest`, `e2eTest` and `wasmTest` are green on the Mac.
- Docs: `docs/<feature>.md` (how it works, enable, disable, test, limitations) plus a README section.
- No new dependency without Ivan's approval. Every third-party library sits behind our own interface.
- After each item: tick port-plan.md and PORTING.md, update test counts, commit with the code (local only).

## Why this order
- Phase 9 (hooks) depends on phase 8. `src/hooks/mod.rs` dispatch (2830 lines, 55 tests) calls the
  guard, read, cmd, memory and docs plugins, and `push.rs` needs the store, render and plugin runtime.
- Phase 10 (CLI) depends on 8 and 9. `cli/guard.rs`, `cli/memory.rs`, `cli/graph.rs`, `cli/docs.rs`
  and `cli/hook.rs` are thin wrappers over those plugins and the hook server.
- Phase 7 (docs) goes first because it only documents what already exists and sets up the doc
  pipeline that later phases add to. It blocks nothing.
- Code dependency that bends the order: `read` and `graph` need tree-sitter (13 grammars,
  cinterop + vendored C). This needs Ivan's approval before phase 8 starts; until then, port
  `docs`, `guard`, `cmd`, `memory` and the Wasm host first, then `read`, then `graph`.

## Phase 7 — docs and site (Dokka, examples)
**Goal.** Documentation for everything ported so far, so phases 8–11 only add to it.

**Scope.**
- Dokka 2 is already set up (`./gradlew dokkaGenerate` → `build/dokka/html`, docs/api-docs.md).
  This phase adds KDoc to public APIs of `:rtok-plugin-sdk`, `:rtok-config`, `:rtok-store`,
  `:rtok-hook`, `:rtok-log`, `:rtok-sys`, `:rtok-wasm` and publishes the HTML as a static site
  (location to be agreed with Ivan).
- Fill gaps in existing `docs/*.md` (18 files today: archive, checkpoint-inject, compress, config,
  hooks, json-tree, store, toon, tokens, testing …) to the standard template.
- Examples, from scratch (no `examples/` exists yet): config TOML samples mirroring Rust
  `docs/`/README, a CLI session (`plugins`, `config`, `archive rewrite --stdin`), a minimal
  plugin against `rtok-plugin-sdk`.
- README: one section per ported feature, linked to its doc.

**Done when.** `dokkaGenerate` runs with no undocumented-public-API warnings in the listed modules;
every ported feature in PORTING.md has a doc in the template; examples run as written (covered by
an e2e case each); test counts unchanged and green.

**Depends on.** Phases 0–6 as they stand. No code dependency.

## Phase 8 — remaining plugins + Wasm host (Chasm)
**Goal.** All of `src/plugins` ported, so the registry catalogue test becomes a full-equality check.

**Scope** (Rust lines / tests; all added from scratch in Kotlin unless noted):
| Rust | Lines | Tests | Kotlin today |
|---|---|---|---|
| `src/plugins/docs` | 707 | 12 | none |
| `src/plugins/guard` | 2844 | 53 | none |
| `src/plugins/cmd` | 10098 | 207 | none |
| `src/plugins/memory` | 5419 | 127 | only `plugins/memory/Private.kt` (from checkpoint) |
| `src/plugins/read` | 4334 | 101 | none; tree-sitter |
| `src/plugins/graph` | 20084 | 277 | none; tree-sitter; store side (`SymbolGraph.kt`, `GraphEvents.kt`) done |
| `src/plugins/wasm.rs` | 478 | 3 | `rtok-wasm` module with `WasmRuntime` interface (41 lines); Chasm adapter from scratch |
| `src/plugins/compress` llm.rs + queue.rs | — | 3 | `Llm.kt` exists; HTTP via the Ktor client task |
Also close carried-over items: json_tree 3 MCP `shorten_result` tests (need cmd/mcp), archive 3
wire-fixture tests, guard `cache_key` assertion deferred from `hooks/types.rs`.

Wasm host stays in its own `:rtok-wasm` module, Chasm behind `WasmRuntime`; tests run alone via
`./gradlew wasmTest`. `crates/rtok-wasm-demo-guest` stays excluded; test guests are checked-in `.wasm`
fixtures.

**Done when.** All tests above ported and green (≈ 783 Rust tests); `RegistryTest` catalogue is
exact; one `docs/<plugin>.md` per plugin; PORTING.md rows ✅.

**Depends on.** Phase 4 (store), phase 5 runtime/registry; Ivan's approval for tree-sitter cinterop
(read, graph); Chasm (approved).

## Phase 9 — hook server, push, `rtok hook`
**Goal.** Finish phase 3: rtok answers host agent hooks like Rust rtok.

**Scope.**
- Done already: `hooks/Types.kt` (types.rs 1338 lines, 18/18), `Deadline.kt` (3/3), `Resident.kt`
  (resident.rs 217 lines, 1 → 2), `:rtok-hook` client.
- From scratch: `src/hooks/mod.rs` dispatch (2830 lines, 55 tests), `src/hooks/push.rs` (101 lines,
  0 tests in Rust → add e2e coverage), `src/cli/hook.rs` (66 lines) as `rtok/cli/HookCmd.kt`.
- Carried over: checkpoint's 3 hook-dispatcher tests and the inject hook-path halves (phase 6 notes).

**Done when.** 55 dispatch tests + 3 checkpoint tests green; e2e: `rtok hook` driven by recorded
host JSON for each of the 9 hosts in `Types.kt`, through the resident socket and directly; push
round trip; phase 3 boxes ticked; docs/hooks.md and docs/hook-protocol.md updated.

**Depends on.** Phase 8 (dispatch calls plugins), phase 4 store, phase 5 runtime.

## Phase 10 — remaining CLI commands (with their backends)
**Goal.** Every non-excluded `src/cli/*.rs` subcommand works, with the Rust module behind it.

**Kotlin today.** `cli/Mod.kt`, `Spec.kt`, `CliktAdapter.kt`, `ConfigCmd.kt` (init/path/validate/set),
`Plugins.kt`, `ArchiveCmd.kt`; CliSpec 17, e2e 11.

**Scope — commands** (Rust `src/cli` file lines): stats 140, info 31, expand 41, logs 85, report 118,
completions 82, man_page 28, run_cmd 112, config show/get (config.rs 181), plugins --json, guard 45,
memory 202, graph 461, docs 26, discover 33, proxy 51, mcp 170, agents 531 + agents_host 333,
doctor 143, otel 58, worktree 407, task 389, batch 84, demon 64, bench 85, util 40, mod.rs 289,
tests.rs (5 tests).

**Scope — backends added from scratch** (Rust lines / tests): `src/proxy` 6953 / 102 (only
`proxy/Anthropic.kt` ToolResults exists), `src/mcp` 2351 / 21 + `crates/rtok-mcp` 1141 / 8,
`crates/rtok-agent-sdk` 2606 / 49, `src/agents` 32357 / 543 (40+ hosts; `junk_web.rs` excluded),
`src/doctor` 5628 / 98, `src/measure` 7742 / 84, `src/model` 3876 / 33, `src/report` 3104 / 26
(text/JSON only, no PDF/SVG), `src/otel` 1748 / 17, `src/worktree` 5323 / 36, `src/tasks` 7995 / 92,
`src/completions` 788 / 15, `src/bench` 599 / 7, `batch.rs` 315 / 4, `demon.rs` 1164 / 15.
Work in that order (proxy → mcp → agents → doctor → rest); each subgroup is its own commit.
Excluded: `web`, `tui`, `dashboard` (`cli/web.rs`).

**Done when.** Each subcommand has a Kotest spec + an e2e case (phase 6 rule); unit tests of the
backends ported and green; Rust `tests/*.rs` (138 files) ported as `:rtok` integration tests where
they target ported features; `--help` output and exit codes match Rust; docs per command.

**Depends on.** Phases 8 (guard/memory/graph/docs/cmd), 9 (`hook`, `agents install` writes hook
config), 4 (store); Ktor client/server approval for proxy and MCP HTTP.

## Phase 11 — release, publishing, CI
**Goal.** Shippable `rtok` binaries interchangeable with Rust rtok.

**Scope.**
- CI (GitHub Actions, once a remote exists): macosArm64 + linuxX64 + linuxArm64 (`-Prtok.allTargets=true`),
  all four test groups, `dokkaGenerate`, build/configuration cache.
- Release: `linkReleaseExecutable` per target, checksums, versioning, changelog; packaging matching
  Rust release artifacts.
- Interop check: Rust and Kotlin binaries read each other's `rtok.db`, payload files, config TOML,
  log files.
- Size/test report (old phase 11): binary sizes, test counts Rust vs Kotlin, build timings.

**Done when.** CI green on all targets; release dry run produces artifacts; interop e2e passes;
port-plan.md/PORTING.md show full status; no pushing or publishing without Ivan's explicit go.

**Depends on.** Phases 7–10.
