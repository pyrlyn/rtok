# Toolchain (mise)

Every tool rtok-kotlin needs that [mise](https://mise.jdx.dev) can provide is pinned in the repo-local
`mise.toml`. Kotlin libraries and klibs (Ktor, ktoml, Room KMP, Chasm, Kotest, ...) stay in Gradle and
come from Maven Central. The build system is Gradle.

| Package | Where | Source | Why here |
|---|---|---|---|
| `pyrlyn.kit:semver`, `toml`, `toml-aot`, `yaml-aot`, `db`, `db-room` | local (included build, `KOTLIN_KIT_DIR` or `packages/kotlin-kit` above the checkout) | https://github.com/pyrlyn/packages (`kotlin-kit/`) | Version ordering, TOML and YAML reading and editing, the SQLite store driver, shared with ketch and backend |

## Enable
```sh
mise install          # installs the pinned JDK, Kotlin, Gradle, Rust, cmake, ninja, tree-sitter CLI
./gradlew build       # with mise activated in the shell, or: mise exec -- ./gradlew build
```

| Tool | Why |
|---|---|
| java (Temurin LTS) | runs Gradle and the Kotlin daemon |
| kotlin | `kotlin` CLI for the `.main.kts` CI scripts; same version as the Gradle plugin |
| gradle | matches `gradle/wrapper/gradle-wrapper.properties` (the wrapper stays the entry point) |
| rust | builds the Rust wasm demo guest used by WASM plugin tests |
| cmake, ninja | native C builds (tree-sitter grammars, zstd) |
| tree-sitter | CLI to regenerate a grammar's `parser.c` when needed |

Not in mise: zstd has no mise package; its C sources are built by Gradle cinterop.

## Update
Bump the version in `mise.toml`; for Gradle also run `./gradlew wrapper --gradle-version <v>`.
The global `~/.config/mise/config.toml` is never touched.

## Disable
Without mise, install the same tools by hand; Gradle picks up `JAVA_HOME`.

## Coverage (`mise run coverage`, dev only)
`mise run coverage` runs `scripts/coverage.py --c`. It never changes the product: the switches it uses
(`-Prtok.coverage=true`, `RTOK_TS_COVERAGE=1`) are off in every normal, test and release build.

- **Kotlin**: Kotlin/Native 2.4 has no coverage instrumentation (the old `-Xcoverage` flags are gone), so
  Kover 0.9.11 measures a JVM target that only `-Prtok.coverage=true` adds, for the modules whose sources
  are all common (`jvmCoverable` in the root `build.gradle.kts`). Native-only modules (`rtok`, `rtok-sys`,
  `rtok-store`, …) are reported as n/a.
- **C**: the tree-sitter runtime (`lib.c`) is built with `-fprofile-instr-generate -fcoverage-mapping`, the
  `:treesitter-native` test binary is linked with clang's profile runtime, and `llvm-cov` (Xcode) reports it.
- Output: a per-module table plus totals in the terminal; HTML in `build/reports/coverage/jvm-html/`
  and `build/reports/coverage/c-html/`. Without `--c` (`python3 scripts/coverage.py`) only Kover runs.

Sample (2026-10-26):
```
module               lines  covered  line %
humantime               33       30   90.9%
toml-ktoml              72       63   87.5%
wasm-chasm              36       22   61.1%
rtok-plugin-sdk        243       54   22.2%
TOTAL (Kotlin/JVM)     530      176   33.2%
tree-sitter C         9288     5311   57.2%
```

## Affected runs: why scripts/smart.py and not Dropbox affectedmoduledetector
Checked 2026-10-26 with 0.6.2 (latest): the repository was archived on 2026-06-16; its open issues
include Gradle 9.2 and KMP support; with our Gradle 9.8.2 build the `runAffectedUnitTests` task discards
the configuration cache ("incompatible task") and wires only Android test task names
(`testDebugUnitTest`), so it ran nothing for `macosArm64Test`. `scripts/smart.py` stays.

## Release builds: full vs fast

| Command | Gradle | What it builds |
|---|---|---|
| `mise run release` | `./gradlew :rtok:linkReleaseExecutableMacosArm64` | Default, for shipping: whole-program optimization (LTO), no release cache. ~105 s link, 18.0 MB. |
| `mise run release-fast` | same + `-Prtok.release.fast=true` | Dev/CI only, never ship: links cached per-library `.a` (K/N `enableReleaseBinaryCache`, experimental). ~15 s warm link, 21.8 MB, CPU-heavy commands 8–14% slower. |

The flag is read in one place, the root `build.gradle.kts` (`releaseFast`); `gradle.properties` pins `rtok.release.fast=false`,
so the release cache is never on by default. Check either binary end to end with
`./gradlew :rtok-e2e:e2eTest -Prtok.e2e.bin=<path-to-rtok.kexe>`. Details: docs/experiments/binary-split.md.
