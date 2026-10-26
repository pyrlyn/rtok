# Experiment: binary + static libraries instead of one monolithic link

Branch `experiment/binary-split`, measured 2026-10-26 on Ivan's Mac (Apple Silicon, macosArm64), Kotlin/Native 2.4.21, Gradle 9.8.2.
Baseline: `origin/main` @ `15f65e4` in a separate clean worktree. Script: `scripts/bench-binary-split.sh <worktree> <label>`.
Not a release decision; nothing on `main` changes.

## What Kotlin/Native and Gradle actually support

| Option | Verdict |
|---|---|
| `binaries.staticLib` / `sharedLib` per module | Not usable for splitting Kotlin modules. Each such library embeds its own K/N runtime and GC and exports only a C API, so Kotlin types cannot cross the boundary and two of them in one process means two runtimes. ([native-dynamic-libraries](https://kotlinlang.org/docs/native-dynamic-libraries.html), [native-faq](https://kotlinlang.org/docs/native-faq.html)) |
| Compiler caches (`kotlin.native.cacheKind=static`, already on in main) | Debug only by default: every dependency klib is precompiled into a static `.a` under `~/.konan/.../klib/cache`, project modules into per-file `.a` under `build/kotlin-native-ic-cache` (`kotlin.incremental.native=true`). This *is* "binary + static libs" and is already in use for debug. ([compilation-model.md](https://github.com/JetBrains/kotlin/blob/master/docs/native/compilation-model.md), [improving compilation time](https://kotlinlang.org/docs/native-improving-compilation-time.html)) |
| `kotlin.native.binary.enableReleaseBinaryCache=true` + `kotlin.internal.native.enableReleaseBinaryCache=true` | Experimental since 2.4.20. Release binaries link prebuilt optimized per-klib `.a` caches (`macos_arm64-optSTATIC`) instead of one LTO whole-program compile. **Implemented on this branch.** ([binary options](https://kotlinlang.org/docs/native-binary-options.html), [improving compilation time](https://kotlinlang.org/docs/native-improving-compilation-time.html#enable-caches-for-release-binaries)) |
| Partial linkage (`-Xpartial-linkage`) | On by default since 1.9.20; it tolerates ABI mismatches between klibs. It is not a separate-linking mechanism and gives no build-time win. ([klib partial linkage](https://kotlinlang.org/docs/whatsnew1920.html#library-linkage-in-kotlin-native)) |
| Native C deps as `.a` via cinterop `staticLibraries` | Supported ([native-definition-file](https://kotlinlang.org/docs/native-definition-file.html#include-a-static-library-in-your-klib)). On main the only C code is the `rtok_*` helpers in `spawn.def`; Chasm is pure Kotlin; `sqlite-bundled` already ships its SQLite as a prebuilt static lib inside its klib; zstd and tree-sitter are not on main yet. **Implemented for the helpers** (`librtok_spawn.a`, built by `:rtok-sys:compileSpawnC`). |

## Changes on the branch

- Release cache is opt-in: `-Prtok.release.fast=true`, read only in the root `build.gradle.kts`, sets `kotlin.internal.native.enableReleaseBinaryCache` and the `enableReleaseBinaryCache` binary option on release binaries. Default (`rtok.release.fast=false` in `gradle.properties`) is unchanged: full LTO, no cache. `mise run release` / `mise run release-fast`.
- `:rtok-e2e:e2eTest -Prtok.e2e.bin=<path>` runs the e2e suite against any binary.
- `rtok-sys`: the C helpers moved from inline code in `spawn.def` to `src/nativeInterop/c/rtok_spawn.{c,h}`, compiled with `xcrun clang` into `build/native/macosArm64/librtok_spawn.a` and linked via `staticLibraries` + `-libraryPath`. macOS arm64 only for now.

## Results (median of 3, `--no-build-cache`, warm daemon and warm `~/.konan` dependency caches)

| Metric | main | branch | Δ |
|---|---|---|---|
| Clean debug build `:rtok:linkDebugExecutableMacosArm64` | 28.5 s | 20.7 s | −27% (likely mostly noise; debug path is unchanged) |
| Incremental debug (touch `rtok-sys/.../Fs.kt`) | 12.5 s | 12.3 s | ≈ |
| Release link `linkReleaseExecutableMacosArm64 --rerun` | 109.9 s | 13.3 s | **−88% (8.3×)** |
| Release binary | 17.9 MB | 21.7 MB | +22% |
| Debug binary | 38.4 MB | 38.4 MB | ≈ |
| `librtok_spawn.a` | — | 1.3 KB | |
| `rtok --version` (hyperfine, 50 runs) | 7.1 ms | 6.8 ms | ≈ |
| `rtok plugins` | 17.7 ms | 19.1 ms | +8% |
| `rtok config validate` | 27.7 ms | 31.7 ms | +14% |
| Peak RSS `--version` / `plugins` / `config validate` | 9.0 / 20.4 / 26.3 MB | 9.8 / 21.4 / 26.1 MB | ≈ +0–9% |

Raw runs: clean debug 28.5/29.8/23.4 vs 21.2/20.7/20.7; incremental 12.4/12.5/12.9 vs 12.8/12.3/11.8; release 116.0/109.9/102.1 vs 15.9/13.3/12.9.
The first release build on the branch still has to build the optimized dependency caches once (≈85 s cold); after that they live in `~/.konan` and are reused.
Another worker was building in a different checkout on the same Mac, so absolute numbers carry some noise.
`check` + `:rtok-e2e:e2eTest` are green on the branch.

## Conclusion

- The real lever is the release binary cache: release link drops from ~110 s to ~13 s, at the cost of +22% binary size and 8–14% slower CPU-heavy commands (no cross-module LTO).
- Moving C code into separate `.a` files works but changes nothing measurable today; it becomes relevant only when zstd/tree-sitter grammars land (build them once with CMake, link via `staticLibraries`).
- Splitting Kotlin modules into separate static/shared libraries is not viable in K/N (one runtime per library, C API only).

Recommendation: keep LTO for shipped releases; enable the release cache for local/CI non-shipping release builds (e.g. a `-Prtok.fastRelease` switch), and reuse the `staticLibraries` pattern for tree-sitter.

## Follow-up: opt-in flag (after merging origin/main)

Same Mac, warm, `--no-build-cache`, `linkReleaseExecutableMacosArm64 --rerun`, 3 runs:

| | default (`mise run release`) | `mise run release-fast` |
|---|---|---|
| Release link | 105 / 104 / 103 s, median 104 s | 15 / 14 / 19 s, median 15 s |
| Binary | 17,968,472 B | 21,791,928 B |
| e2e (`-Prtok.e2e.bin=`) | 14/14 passed | 14/14 passed |

Shipped releases keep using the default; the fast flag is for local iteration and non-shipping CI artifacts.
