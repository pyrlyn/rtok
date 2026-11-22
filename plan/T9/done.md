# T9. Done

Dead leftovers of the Rust port are removed. Every removal was checked with a reference search over
main, test and build files first; a candidate with a live reader stayed. Stacked on T7 (#289).

## Removed

- **Unused code**: `Store.openCount`; the C wrapper `rtok_spawn_addchdir` (the static helper `rtok_spawn`
  uses stays); `naiveAlwaysMinimum`; `Config.pathFields`; `Layers.onEarlyWarning` and its hook, which
  nothing ever registered.
- **Duplicates, one copy kept**: `isFile` in rtok-config (rtok-sys has it); `Tools.pingText`
  (`Ping.pingText`); `Hooks.pluginInstallPaths` (`Advice.pluginInstallPaths`); `Answer.tagReply` and its
  test (rtok-web has both); a doubled `mjs` MIME row.
- **`TaskAdapter.maxId`**: the Rust id allocator. Ids come from the store counter now, so only tests
  called it. Its five implementations and `maxWithPrefix` went with it.
- **Stale comments**: file headers that said a part "waits" or "lands later" although it is ported
  (`Llm.kt`, `McpSurfaces.kt`, `Backup.kt`); two orphan KDoc lines; unused imports in the files touched
  and in the four `Junk*.kt` files that shared one copied import block.
- **Build config**: `rtok.coverage.modules` named `toml`, `wasm` and `wasm-chasm`, which are not modules
  of this build any more.

## Fixed on the way

`mise run coverage` was broken by the moves to shared packages, in three places:

- `scripts/coverage.py` read the module list from a `val jvmCoverable` in `build.gradle.kts`; the list
  lives in `gradle.properties` now, and the script reads it there.
- `toml-ktoml` cannot build for the JVM any more: its `pyrlyn.kit:toml` comes from an included build
  without a JVM target. It left the list.
- `rtok-plugin-sdk` depends on `json-strict`, which was not in the list. It is now.

The Kotlin half runs again (`python3 scripts/coverage.py`); the sample in `toolchain.md` is its output.
The C half (`--c`) was not rerun.

## Kept, and why

- **Windows branches** (`wrapQuote(windows)`, `resolveShell(windows, comspec)`, `skipWrapHost(windows)`,
  `ShellKind.Cmd`): every caller passes `false` today, but the `mingwX64` target is on the roadmap.
- **Test seams**: functions only tests call but that exist to make code testable (`LspWire.frames`,
  `Migrations.runBefore`, `evaluateLadder`, `Filter.run`, `seedHostMessages` and the like).
- **Public SDK API**: `skillRefs`, `Size.Percent` in the TUI library.
- **`@Suppress("UNREACHABLE_CODE") ""` in `Lsp.outline`**: the lambda needs a value after `while (true)`.
- **`build-logic/` and `gradle/libs.versions.toml` in `scripts/smart.py`**: they do not exist, but the
  list is a guard: if one appears, a change to it must trigger a full run.
- Rust format compatibility, `PORTING.md`, `port-plan.md` and the Rust fixtures the tests read.

## Needs the creator's decision

- **Unwired features**: worktree cleanup after a merged PR (`HookLinks.completePendingWorktree`,
  `unlockEndedWorktree`, `closeIfActive`, `Store.finishedWorktreeClaims`) is written but nothing calls
  it; `WasmPlugin.invokeMcp` and `mcpTools`; `PluginCache`. Wire them or delete them.
- **Config keys that are parsed and never read**: `plugins.memory.embed.api_key_env`,
  `estimator.calibrate_samples`. Removing them changes the config format.
- **`:rtok-web-ui`** (with kotlinx-html, kotlin-css and the yarn block): is it still wanted next to
  `:rtok-web-compose`?
- **`rust` in `mise.toml`, `toolchain.md` and `README.md`**, and `scripts/bench-binary-split.sh`: needed
  only for comparing against the Rust binary.
- `TODO.md` (K6) and the T4/T5 mentions; rows in `ideas.md` that were already promoted; stale lines in
  `roadmap.md`.
- A broken task file keeps its id (`create` refuses it), but `TaskProject.seed` does not count it, so
  the counter can hand that id out and the create then fails until the file is fixed.

## Checks

- `./gradlew macosArm64Test cliTest e2eTest --continue`: green (ktlint and detekt run inside it).
- `mise run size-check`: 74,359,592 B of the 79,000,000 B budget, the same as the T7 head: the unused
  code was already stripped at link time.
- `python3 scripts/coverage.py`: runs and prints the table.

The C edit (`rtok_spawn_addchdir` removed) is in a file shared with Linux; CI on the PR is its Linux build.
