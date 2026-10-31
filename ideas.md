# rtok-kotlin ideas

Ideas the creator has not approved yet. Nothing here is a task: an idea moves to `roadmap.md`
or `plan/` only with the creator's approval.

Product features belong in the Rust rtok `ideas.md` first; the port follows Rust so the two
binaries stay compatible. Ideas here are about the port itself: build, tests, docs, layout.

## How to add an idea

One `I-N` row: the area, what is missing, and the evidence (a path, a commit or a command
output). No task, no Check, no branch.

## Open

An idea the creator approved keeps its row, marked **Promoted to T<n>**; the task lives in `plan/`.

### Build and CI

| Id | Area | Idea | Evidence |
|---|---|---|---|
| I-1 | CI | **Promoted to T1.** Add CI for every PR: macOS arm64 and Linux x86_64 jobs running `./gradlew macosArm64Test cliTest e2eTest` (Linux: the matching targets). Today every test run is local and manual, so Linux-only bugs are found by hand. | `gh api repos/pyrlyn/rtok/actions/workflows` returns `total_count: 0`; no `.github/` in the repo; `port-plan.md` P1 lists "Linux CI job (missing)" and four Linux-only bugs. |
| I-2 | detekt | **Promoted to T5.** Keep detekt baselines shrink-only (fail when an entry is added), tune `MagicNumber` (tests ignored, named constants allowed), then cut the baseline file by file starting with the most complex files. | `rtok/detekt-baseline.xml` has 1199 entries: 681 `MagicNumber`, 213 `ReturnCount`, 90 `CyclomaticComplexMethod`, 73 `TooManyFunctions`. Largest files: `plugins/cmd/Rules.kt` 921 lines, `plugins/graph/Diff.kt` 844. |
| I-3 | binary size | **Promoted to T6.** Measure what the release binary is made of (tree-sitter grammars, bundled SQLite, the rest) and add a size budget check; consider making grammars optional. | `rtok/build/bin/macosArm64/releaseExecutable/rtok.kexe` is 108,768,408 bytes; `PORTING.md` lists 34 grammars in `libs/treesitter-native`. |
| I-4 | modules | **Promoted to T4.** Split the `:rtok` module by area (`graph`, `agents`, `cmd`, `memory`, `proxy`) so incremental builds and `scripts/smart.py` affected runs touch less. Not the linking split already measured in `docs/experiments/binary-split.md`. | `:rtok` holds about 70k lines of non-test Kotlin; all other modules together about 25k. Any change in it makes the affected run test the whole app. |

### Shared code

| Id | Area | Idea | Evidence |
|---|---|---|---|
| I-5 | libs / kotlin-kit | **Promoted to T2.** Depend on `packages/kotlin-kit` through `includeBuild` (as with `kotlin-snapshot` and `kotlin-wasm`) and delete the copies in `libs/`. Port local fixes into kotlin-kit first where the copies diverged. | kotlin-kit's README says its modules started as rtok-kotlin's `libs/`. `diff -rq` of `src/`: `semver`, `yaml-aot`, `toml-aot` identical; `toml`, `http`, `db-room` differ. Fix #246 (ktoml escaped quotes) landed here only. |
| I-6 | CLI / fs | **Promoted to T2.** Compare `pyrlyn.kit:cli` (clap 4.6 semantics, completions, man pages) with the Clikt adapter and `cli/Spec.kt`, and `pyrlyn.kit:fs` with `rtok-sys` `Fs.kt`; adopt the kit modules where they cover the port's needs. | `packages/kotlin-kit/README.md` module table; `PORTING.md` rows for `cli/Mod.kt` + `CliktAdapter.kt` and `rtok-sys/nativeMain/Fs.kt`. |

### Port parity and docs

| Id | Area | Idea | Evidence |
|---|---|---|---|
| I-7 | tests | Differential tests: run the Rust and the Kotlin binaries on the same fixtures and compare stdout, hook JSON, log lines and store files. Turns the byte-compatibility claims into a check. | `rtok-log/.../Log.kt` header: line format and rotation "identical to Rust rtok so both binaries can share a log dir"; no Rust-vs-Kotlin harness in `rtok-e2e` or `scripts/`. |
| I-8 | docs | Generate the `PORTING.md` status table (Rust tests vs Kotlin tests per source file) with a script instead of editing it by hand. The repeated table fragment and the stale ⬜ rows found while writing this idea were fixed by hand in T3, which is the cost of the manual upkeep. | `PORTING.md` has about 100 rows edited per feature PR; before T3 it repeated part of the table after the totals and marked `rtok-sys`, `rtok-mcp`, plugins, hooks and MCP ⬜ while other rows said ✅. |
| I-9 | docs | **Promoted to T3.** Bring `README.md` and `port-plan.md` up to date: the TUI and web UI are no longer excluded, and fixed items are still open in the plan. | Before T3, `README.md` said TUI and web UI were excluded, but `libs/tui` and `rtok-web*` modules exist; `port-plan.md` still listed the ktoml, `TuiProjectsTest` and `DemonE2eSpec` items fixed by #246, #248 and #249. |
| I-10 | plan layout | **Promoted to T3.** Move open work from `port-plan.md` checkboxes into `roadmap.md` and `plan/<id>/task.md` folders, and move `docs/toolchain.md` to the project root, as the workspace layout requires. `port-plan.md` stays for decisions and history. | Before T3 the repo root had no `plan/`, `roadmap.md` or `toolchain.md`; `port-plan.md` was a per-session log with the open work as checkboxes. |
| I-11 | targets | Windows `mingwX64` target. Easier after I-5 and I-6: `pyrlyn.kit:fs` already supports Win32. | `port-plan.md` P2: "Windows `mingwX64` target (absent)". |
