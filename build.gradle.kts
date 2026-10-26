// Dokka's coroutines want org.jetbrains:annotations 23 while Gradle's embedded-Kotlin pin says 13.0
// once KSP and Kotest join the build classpath; annotations are compile-time only, so the newer one is safe.
buildscript { configurations.classpath { resolutionStrategy.force("org.jetbrains:annotations:23.0.0") } }
plugins {
    kotlin("multiplatform") version "2.4.21" apply false; kotlin("plugin.serialization") version "2.4.21" apply false
    // API docs: `./gradlew dokkaGenerate` only; never wired into build/test (docs/api-docs.md).
    id("org.jetbrains.dokka") version "2.2.0"
    // Tests: Kotest specs next to kotlin.test (docs/testing.md); KSP must come before Kotest.
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("io.kotest") version "6.2.6" apply false
    // Dev-only coverage (`mise run coverage`, docs/toolchain.md): active only with -Prtok.coverage=true.
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
}

// Coverage builds add a JVM target to the modules whose sources are all common (Kover measures JVM
// bytecode only; Kotlin/Native 2.4 has no coverage instrumentation). Never set for product/release builds.
val coverage = providers.gradleProperty("rtok.coverage").orNull == "true"
val jvmCoverable = setOf("humantime", "toml", "toml-ktoml", "http", "wasm", "wasm-chasm", "rtok-plugin-sdk", "treesitter")

if (coverage) {
    apply(plugin = "org.jetbrains.kotlinx.kover")
    dependencies { jvmCoverable.forEach { add("kover", project(":$it")) } }
    configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
        reports.total.html.htmlDir.set(layout.buildDirectory.dir("reports/coverage/jvm-html"))
        reports.total.xml.xmlFile.set(layout.buildDirectory.file("reports/coverage/jvm.xml"))
    }
}

// Fast release (opt-in, docs/toolchain.md): `-Prtok.release.fast=true` links release binaries from cached
// per-library .a (K/N release binary cache, experimental since 2.4.20) instead of one whole-program LTO compile.
// Unset (the default, and every shipped release) = full optimization, no release cache. Read only here.
val releaseFast = providers.gradleProperty("rtok.release.fast").orNull == "true"

// One aggregated HTML site over every module, declared once here.
dependencies { subprojects.forEach { add("dokka", it) } }
dokka { moduleName.set("rtok-kotlin") }

subprojects {
    // KGP reads this before configuring native link tasks, so it must be set before the plugin is applied.
    if (releaseFast) extra["kotlin.internal.native.enableReleaseBinaryCache"] = "true"
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "com.google.devtools.ksp")
    apply(plugin = "io.kotest")
    // Release binaries are rebuilt only for shipping and are large, so they never enter the
    // build cache (local or remote); debug test binaries stay cacheable (docs/build-speed.md).
    if (coverage && name in jvmCoverable) apply(plugin = "org.jetbrains.kotlinx.kover")
    tasks.matching { it.name.startsWith("linkRelease") }.configureEach {
        outputs.cacheIf("release binaries are not cached") { false }
    }
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
        macosArm64()
        if (releaseFast) targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
            binaries.configureEach { if (buildType == org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE) binaryOption("enableReleaseBinaryCache", "true") }
        }
        if (coverage && project.name in jvmCoverable) jvm()
        if (providers.gradleProperty("rtok.allTargets").orNull == "true") { linuxX64(); linuxArm64() }
        sourceSets.commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
        }
        sourceSets.commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.kotest:kotest-framework-engine:6.2.6"); implementation("io.kotest:kotest-assertions-core:6.2.6")
        }
    }
}
