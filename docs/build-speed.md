# Build speed

All switches live in `gradle.properties`; override per run with `-P<name>=<value>`.

| Setting | Effect | Disable |
|---|---|---|
| `org.gradle.configuration-cache=true` | skips configuration on repeat runs | `--no-configuration-cache` |
| `org.gradle.caching=true` | reuses task outputs | `--no-build-cache` |
| `org.gradle.parallel=true` | modules build in parallel | `--no-parallel` |
| `org.gradle.jvmargs` / `kotlin.daemon.jvmargs` | 4 GB heap for Gradle and Kotlin daemons | edit the value |
| `kotlin.native.cacheKind=static` | precompiled stdlib/dep caches for debug links | set `none` |
| `kotlin.incremental.native=true` | incremental Kotlin/Native compilation | set `false` |
| `rtok.allTargets=false` | only the host target `macosArm64` is configured | `-Prtok.allTargets=true` adds linuxX64, linuxArm64 |

Dev loop: `./gradlew macosArm64Test` (debug test link only). Release binary:
`./gradlew :rtok:linkReleaseExecutableMacosArm64`.
Measured timings are in `port-plan.md` → "Build timings".

## Incremental compilation status (2026-10-26)
Already enabled in `gradle.properties`, no change needed: `kotlin.incremental.native=true`, K2 (default in
Kotlin 2.2.21), `org.gradle.caching=true`, `org.gradle.configuration-cache=true`, `org.gradle.parallel=true`,
`kotlin.native.cacheKind=static`, Gradle daemon.

Measured on the Mac (one-line edit in `rtok/plugins/inject/Inject.kt`, then `:rtok:linkDebugTestMacosArm64`):
8 s before the test-group split, 8 s after. Full `./gradlew build` (warm daemon/cache): 66 s; `e2eTest` 7 s;
`:rtok:macosArm64Test` 7 s; `cliTest` 2 s (shares the already-linked test binary).

## Remote build cache (Cloudflare R2)

`settings.gradle.kts` registers `com.github.burrunan.s3-build-cache` 1.9.10 as a remote `AwsS3BuildCache`
(region `auto`). The local build cache (`org.gradle.caching=true`) still runs in front of it.

**Enable.** Export both keys. Without both of them the remote cache is not registered, and the build behaves
as if the plugin weren't there.

```sh
export S3_BUILD_CACHE_ACCESS_KEY_ID=...   # R2 API token, from the vault / CI secrets; never commit
export S3_BUILD_CACHE_SECRET_KEY=...
export RTOK_REMOTE_CACHE_BUCKET=<bucket>                       # or -Prtok.remoteCache.bucket=
export RTOK_REMOTE_CACHE_ENDPOINT=https://<account>.r2.cloudflarestorage.com   # or -Prtok.remoteCache.endpoint=
export RTOK_REMOTE_CACHE_PREFIX=rtok-kotlin/                   # optional, default rtok-kotlin/
```

**Disable.** Unset either key, or pass `--no-build-cache` to turn off both caches for one run.

**Push.** Only when `CI` is set; local builds only read.

**What is cached.** Every cacheable task except release links: the root `build.gradle.kts` marks all
`linkRelease*` tasks `outputs.cacheIf { false }`. Debug test binaries (`linkDebugTest*`, ~13 MB) stay
cacheable. `maximumCachedObjectLength` stays at the plugin default, which fits them.

**Test.** Run `./gradlew --no-daemon --build-cache --info :rtok:linkDebugTestMacosArm64` twice with the keys set
(`CI=1` the first time). The second run should log `Loaded cache entry`. `linkReleaseExecutableMacosArm64` always
logs `Caching disabled ... 'release binaries are not cached'`.

**Retention (infra, not this repo).** An R2 lifecycle rule deletes entries older than 7-14 days. R2 has no
size quota, so a pruning job also deletes the oldest entries first until the bucket is under
`rtok.remoteCache.maxTotalSize` (`gradle.properties`, currently `10GB`). Both jobs are managed by Ansible in `pyrlyn/infra`.

## Release binary size

Configured via `diffuser.releaseSize=all` in `gradle.properties` (logic in ci-diffuser `NativeConventionPlugin`), release executables only (debug/test binaries untouched).
K/N release already links in LTO mode (all modules optimized together), so no extra LTO flag.

| option | flag | rtok size | link (median of 3) |
|---|---|---|---|
| baseline | – | 17.87 MB | 101 s |
| `small` | `-Xbinary=smallBinary=true` (Oz) | 15.28 MB | 79 s |
| `strip` | `-Wl,-dead_strip,-x,-S` (macOS) / `-Wl,--gc-sections,-s` (Linux) | 12.90 MB | 96 s |
| `latin1` | `-Xbinary=latin1Strings=true` | 17.44 MB | 97 s |
| all (default) | all of the above | 9.95 MB | 86 s |

`rtok --version` startup (hyperfine, 50 runs): 7.0 ms baseline vs 7.1 ms all, within noise.
Pick a subset with `-Pdiffuser.releaseSize=none|all|small,strip,latin1`.
