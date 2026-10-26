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
}

// One aggregated HTML site over every module, declared once here.
dependencies { subprojects.forEach { add("dokka", it) } }
dokka { moduleName.set("rtok-kotlin") }

subprojects {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "com.google.devtools.ksp")
    apply(plugin = "io.kotest")
    // Release binaries are rebuilt only for shipping and are large, so they never enter the
    // build cache (local or remote); debug test binaries stay cacheable (docs/build-speed.md).
    tasks.matching { it.name.startsWith("linkRelease") }.configureEach {
        outputs.cacheIf("release binaries are not cached") { false }
    }
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
        macosArm64()
        if (providers.gradleProperty("rtok.allTargets").orNull == "true") { linuxX64(); linuxArm64() }
        // Release-size options (docs/build-speed.md "Release binary size"): RELEASE binaries only;
        // debug and test binaries are untouched. K/N release already runs LTO (whole-program LLVM opt).
        // -Prtok.releaseSize=none|all|small,strip,latin1 picks a subset (for measuring); default all.
        val sizeOpts = (providers.gradleProperty("rtok.releaseSize").orNull ?: "all").split(',').map { it.trim() }.toSet()
        fun size(o: String) = "all" in sizeOpts || o in sizeOpts
        targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
            binaries.matching { it.buildType == org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE && it !is org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable }.configureEach {
                if (size("small")) freeCompilerArgs += "-Xbinary=smallBinary=true"
                if (size("latin1")) freeCompilerArgs += "-Xbinary=latin1Strings=true"
                if (size("strip")) linkerOpts(if (konanTarget.family == org.jetbrains.kotlin.konan.target.Family.OSX) listOf("-Wl,-dead_strip", "-Wl,-x", "-Wl,-S") else listOf("-Wl,--gc-sections", "-Wl,-s"))
            }
        }
        sourceSets.commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
        }
        sourceSets.commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.kotest:kotest-framework-engine:6.2.6"); implementation("io.kotest:kotest-assertions-core:6.2.6")
        }
    }
}
