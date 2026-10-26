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

## Limitations
- Native Kotest has no annotation-based config (Kotlin/Native has no runtime annotations).
- Native incremental caches must be cleaned once after a Kotlin version change.
