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
