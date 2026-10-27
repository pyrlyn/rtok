// Dokka's coroutines want org.jetbrains:annotations 23 while Gradle's embedded-Kotlin pin says 13.0
// once KSP and Kotest join the build classpath; annotations are compile-time only, so the newer one is safe.
buildscript { configurations.classpath { resolutionStrategy.force("org.jetbrains:annotations:23.0.0") } }
plugins {
    kotlin("multiplatform") version "2.4.21" apply false; kotlin("plugin.serialization") version "2.4.21" apply false
    // Compose Multiplatform for the web UI (:rtok-web-compose, wasmJs only), approved by Ivan 2026-10-27.
    kotlin("plugin.compose") version "2.4.21" apply false; id("org.jetbrains.compose") version "1.12.2" apply false
    // API docs: `./gradlew dokkaGenerate` only; never wired into build/test (docs/api-docs.md).
    id("org.jetbrains.dokka") version "2.2.0" apply false
    // Tests: Kotest specs next to kotlin.test (docs/testing.md); KSP must come before Kotest.
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("io.kotest") version "6.2.6" apply false
    // Dev-only coverage (`mise run coverage`, docs/toolchain.md): active only with -Prtok.coverage=true.
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
    // Shared Kotlin/Native dev/release settings: targets, release cache rules, -Prtok.releaseSize, -Prtok.release.fast.
    id("ci.diffuser.native")
    // Shared project config: KMP/serialization/Dokka/Kotest per subproject, Dokka aggregation, opt-in Kover (-Prtok.coverage=true).
    id("ci.diffuser.kmp")
}

extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> { moduleName.set("rtok-kotlin") }

// Tests never see the real home: every native test run and the e2e suite get a per-module temp HOME and
// RTOK_HOME under build/, and fail if anything created a literal `~` directory in the module (unexpanded path).
subprojects {
    val testHome = layout.buildDirectory.dir("test-home")
    val strayTilde = layout.projectDirectory.dir("~").asFile
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
        val h = testHome.get().asFile
        doFirst { h.resolve(".rtok").mkdirs() }
        environment("HOME", h.absolutePath)
        environment("RTOK_HOME", h.resolve(".rtok").absolutePath)
        doLast { if (strayTilde.exists()) throw GradleException("test created a literal '~' directory: $strayTilde") }
    }
}

// Kotlin/JS (the js target of :rtok-web-model) keeps its yarn.lock under build/, so a gate run writes nothing into the
// source tree (the default is kotlin-js-store/ at the root). The lock is regenerated from Gradle pins.
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    extensions.configure<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension> {
        lockFileDirectory = layout.buildDirectory.dir("kotlin-js-store").get().asFile
    }
}

// Test commands (docs/testing.md "Agent tests"): `agentTest` = agent tests only (default while working on
// agents), `testNoAgents` = everything except them, `fullTest` = the whole suite (same as the merge gate).
tasks.register("agentTest") {
    group = "verification"
    description = "Runs only the agent tests (:rtok:agentTest)."
    dependsOn(":rtok:agentTest")
}
tasks.register("testNoAgents") {
    group = "verification"
    description = "Runs every test except the agent tests: unit tests of all modules, :rtok unit+CLI without agents, e2e."
    dependsOn(":rtok:noAgentTest", ":rtok-e2e:e2eTest")
    subprojects.filter { it.path != ":rtok" }.forEach { p -> dependsOn(p.tasks.matching { it.name == "macosArm64Test" }) }
}
tasks.register("fullTest") {
    group = "verification"
    description = "Runs the whole suite including the agent tests (macosArm64Test cliTest e2eTest)."
    subprojects.forEach { p -> dependsOn(p.tasks.matching { it.name == "macosArm64Test" || it.name == "cliTest" || it.name == "e2eTest" }) }
}
