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
    // Shared Kotlin/Native dev/release settings: targets, release cache rules, -Prtok.releaseSize, -Prtok.release.fast.
    id("ci.diffuser.native")
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

// One aggregated HTML site over every module, declared once here.
dependencies { subprojects.forEach { add("dokka", it) } }
dokka { moduleName.set("rtok-kotlin") }

subprojects {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "com.google.devtools.ksp")
    apply(plugin = "io.kotest")
    if (coverage && name in jvmCoverable) apply(plugin = "org.jetbrains.kotlinx.kover")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
        if (coverage && project.name in jvmCoverable) jvm()
        sourceSets.commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
        }
        sourceSets.commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.kotest:kotest-framework-engine:6.2.6"); implementation("io.kotest:kotest-assertions-core:6.2.6")
        }
    }
}
