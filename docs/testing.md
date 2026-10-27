# Testing

## How it works
Two test styles live side by side in every module, configured once in the root `build.gradle.kts`
(`subprojects {}` convention):
- **kotlin.test** (`@Test` classes): every test ported from Rust keeps this form, unchanged.
- **Kotest 6.2.6** (`io.kotest` Gradle plugin + KSP `2.2.21-2.0.5`, `kotest-framework-engine` and
  `kotest-assertions-core` in `commonTest`): new specs (`FunSpec`, `shouldBe`, …). KSP generates the
  native launcher for specs; both kinds run in the same `macosArm64Test` binary and report to the
  same JUnit XML (`build/test-results/macosArm64Test`).

Kotest is a test-only dependency, so it is not wrapped in an adapter.

Toolchain notes: Kotest 6.2.6 brings kotlin-stdlib 2.2.21, so Kotlin is 2.2.21 (from 2.2.20) to
keep the stdlib and the native compiler in step; after that bump a one-time `./gradlew clean` is
needed (stale native caches fail at link time with `knifunptr_platform_posix…`). The root
buildscript forces `org.jetbrains:annotations:23.0.0` on the plugin classpath (Dokka vs Gradle's
embedded-Kotlin pin once KSP joins).

## Enable / disable
On by default for every module. To drop Kotest: remove the two plugin lines, their `apply`
lines and the two `io.kotest` dependencies in the root `build.gradle.kts` (kotlin.test tests are
unaffected). End-to-end CLI tests are a separate task (below).

## Test
- Unit + spec tests: `./gradlew macosArm64Test` (or `:module:macosArm64Test`).
- Report per module: `python3 ~/Grok/rtok-kotlin-port/failures.py <module dir>`.

Measured on the Mac (one edit, warm daemon, seconds):

| task | before Kotest | with Kotest |
| --- | --- | --- |
| `:rtok:macosArm64Test` | 7.9, 5.0 | 16.3 (first), then 2.4–2.6 |
| `:rtok-store:macosArm64Test` | 13.6 | 15.4–16.4 |

KSP adds roughly 1.5–3 s per changed test compilation; a full clean `macosArm64Test` took 65 s
(with the build cache).

## CLI tests
- In-process: `rtok/src/nativeTest/kotlin/rtok/cli/CliSpec.kt` (Kotest, 17) drives `run(args, io)` with a captured `CliIo`; covers parsing (incl. `--since` through a toy spec), usage errors (exit 2), `Error: …` (exit 1), log/project root/config integration. Tests target our `CommandSpec` layer, not Clikt.
- End-to-end: module `rtok-e2e` (Kotest, 11, no mocks). `./gradlew e2eTest` links `:rtok:linkDebugExecutableMacosArm64`, runs the real `rtok.kexe` in temp dirs (own `RTOK_HOME`/`HOME`/git env) against real git repos (main checkout, worktree, submodule), asserting stdout, stderr, exit code and files written (log, config). Not part of `macosArm64Test` (skipped there). Output: `rtok-e2e/build/e2e/output.txt`.
- Timing (Mac, one edit in `rtok/cli`): `:rtok:macosArm64Test` 7.7–8.8 s, `e2eTest` 7.7 s.

- Native Kotest has no annotation-based config (Kotlin/Native has no runtime annotations).
- The Kotest native launcher exits 0 on failures; `e2eTest` parses the `Tests: N passed, M failed` line and fails the build.
- Usage-error texts come from Clikt and differ from clap's wording (exit codes match).
- `--since` against a real repo in e2e waits for Phase 6 `stats`/`discover`; `plugins --json`, `config show/get` wait for the model.
- Native incremental caches must be cleaned once after a Kotlin version change.

## Test groups
Each group is its own Gradle task and can run alone:

| Group | Command | What runs |
|---|---|---|
| unit | `./gradlew macosArm64Test` (one module: `./gradlew :rtok:macosArm64Test`) | kotlin.test + Kotest unit tests of every module; in `:rtok` the CLI specs are excluded (`filter.excludeTestsMatching("rtok.cli.*")`, `KOTEST_TAGS=!Cli`) |
| CLI | `./gradlew cliTest` | `:rtok` Kotest CLI specs (tag `Cli`) and `rtok.cli.*` tests, same test binary, `KOTEST_TAGS=Cli` |
| e2e | `./gradlew e2eTest` | `:rtok-e2e` Kotest specs against the linked `rtok.kexe` |
| wasm | `./gradlew wasmTest` | `:rtok-wasm` (WASM plugin host behind our `WasmRuntime` interface; the Chasm adapter joins it) |

`./gradlew build` runs unit + CLI (`check` depends on `cliTest`); e2e and wasm stay explicit.
Kotest on Native ignores Gradle's test filter, so a new CLI spec must call `tags(Cli)`.

## Snapshot tests

Snapshot assertions come from the kotlin-snapshot package (`pyrlyn.snapshot:snapshot`, an included
build at `../../packages/kotlin-snapshot`, overridden by `KOTLIN_SNAPSHOT_DIR`), written after Rust's
insta. It reads and writes insta's `.snap` format, so the Rust `.snap` files are reused as they are.

- File snapshots: `assertSnapshot(name, actual)`, files in `rtok/src/commonTest/snapshots`
  (`SNAPSHOT_DIR`), named `<prefix>__<name>.snap` like insta.
- Inline snapshots: `assertInlineSnapshot(actual, """ ... """)` for Rust's `@"..."` snapshots.
- A changed snapshot fails with a line diff and writes `<name>.snap.new` (ignored by git). Review
  it and rename it, or rerun with `SNAPSHOT_UPDATE=always` to accept. With `CI` set nothing is
  written.

Ported so far: `tests/compress_snapshot.rs` (`CompressSnapshotTest`, the Rust `.snap` unchanged)
and the inline snapshots of `removal_preview.rs`, `worktree/list.rs` and `plugins/checkpoint.rs`.
`report/{html,markdown}.rs` and `worktree/clean.rs` follow when those are ported.

Where tests live: Packages/libraries contain only tests of the package itself. Tests that verify the app (rtok-kotlin) through a package live in the app next to its other tests. Applies to the whole Kotlin port, including snapshot tests.

## Linux

`-Prtok.allTargets=true` adds linuxX64/linuxArm64; every C library is cross-compiled per target (buildSrc `CrossCc`).

- On a Linux x64 host the usual tasks run there: `linuxX64Test`, `cliTest` and `e2eTest` pick the host target.
- From the Mac, `-Prtok.test.target=linuxArm64` makes `e2eTest` link for that target; run the binaries in a Lima VM
  with `scripts/lima-test.sh` (Kotlin/Native has no Linux arm64 host):

```
./gradlew -Prtok.allTargets=true linkDebugTestLinuxArm64 :rtok:linkDebugExecutableLinuxArm64
LIMA_VM=rtok-linux LIMA_MOUNT=~/lima-rtok scripts/lima-test.sh            # all modules, or name some
```

Results land in `$LIMA_MOUNT/rtok-kotlin/build/lima-results/`.
