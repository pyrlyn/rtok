@file:OptIn(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi::class)

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.KonanTarget
// rules/default.toml is copied verbatim from the Rust repo's rules/default.toml (the built-in cmd rules).
val defaultRules = embedText("genDefaultRules", "rules/default.toml", "rtok.plugins.cmd", "DEFAULT_RULES", "DefaultRules")
// -Prtok.wasm=true (docs/wasm-plugins.md): Kotlin has no conditional compilation, so the flag picks a source dir.
val wasmPlugins = providers.gradleProperty("rtok.wasm").orNull == "true"
val wasmDir = if (wasmPlugins) "wasmPlugins" else "noWasmPlugins"
configure<KotlinMultiplatformExtension> {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            entryPoint = "rtok.main"
            baseName = "rtok"
            (findProperty("rtok.linkMap") as String?)?.let { linkerOpts("-Wl,-map,$it") }
        }
        // The static caches of clikt and clikt-mordant both define Context.selfAndAncestors, so ld.lld fails with a
        // duplicate symbol. Kotlin 2.3.20 removed the kotlin.native.cacheKind.<target> property; this is its DSL.
        if (konanTarget == KonanTarget.LINUX_X64) {
            binaries.configureEach {
                disableNativeCache(
                    DisableCacheInKotlinVersion.`2_4_21`,
                    "clikt and clikt-mordant caches both define Context.selfAndAncestors (duplicate symbol at link)",
                )
            }
        }
    }
    sourceSets.commonMain
        .get()
        .kotlin
        .srcDir(defaultRules)
    sourceSets.nativeMain { kotlin.srcDir("src/$wasmDir/kotlin") }
    sourceSets.nativeTest { kotlin.srcDir("src/${wasmDir}Test/kotlin") }
    sourceSets.commonMain.dependencies {
        implementation(project(":rtok-plugin-sdk"))
        implementation(project(":rtok-config"))
        implementation(project(":rtok-sys"))
        implementation(project(":semver"))
        implementation(project(":humantime"))
        implementation(project(":globset"))
        implementation(project(":json-strict"))
        implementation(project(":toml-aot"))
        implementation(project(":yaml-aot"))
        implementation(project(":rtok-agent-sdk"))
        implementation(project(":rtok-mcp"))
        implementation(project(":rtok-store"))
        implementation(project(":db-room"))
        implementation(project(":rtok-log"))
        implementation(project(":http"))
        implementation(project(":http-ktor"))
        implementation(project(":http-server-ktor"))
        implementation(project(":rtok-web"))
        implementation(project(":tui"))
        if (wasmPlugins) {
            implementation(project(":rtok-wasm"))
            implementation("pyrlyn.wasm:wasm-chasm")
        }
        implementation(project(":treesitter-native"))
        implementation(project(":zstd-native"))
        implementation("com.github.ajalt.clikt:clikt:5.1.0")
    }
    sourceSets.commonTest.dependencies { implementation("pyrlyn.snapshot:snapshot") }
}
val fixtures =
    layout.projectDirectory
        .dir("src/commonTest/fixtures")
        .asFile.absolutePath
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("RTOK_FIXTURES", fixtures)
    environment("RTOK_WASM_FIXTURES", rootDir.resolve("rtok-wasm/src/nativeTest/fixtures").absolutePath)
    // Snapshot tests (docs/testing.md): `.snap` files next to the tests; SNAPSHOT_UPDATE=always accepts changes.
    environment(
        "SNAPSHOT_DIR",
        layout.projectDirectory
            .dir("src/commonTest/snapshots")
            .asFile.absolutePath,
    )
    listOf("SNAPSHOT_UPDATE", "INSTA_UPDATE", "CI").forEach { name -> System.getenv(name)?.let { environment(name, it) } }
}

// Test groups (docs/testing.md): `macosArm64Test` = unit tests without the CLI specs, `cliTest` = the
// Kotest CLI specs (package rtok.cli, tagged `Cli`; Kotest ignores Gradle's filter, so KOTEST_TAGS selects them) from the same test binary; e2e lives in :rtok-e2e (`e2eTest`).
val cliPattern = "rtok.cli.*"
// The host's own native test task (macosArm64Test on the Mac, linuxX64Test on a Linux x64 host with -Prtok.allTargets=true).
val hostTarget =
    when (org.jetbrains.kotlin.konan.target.HostManager.host) {
        org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_X64 -> "linuxX64"
        else -> "macosArm64"
    }
tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeHostTest>("${hostTarget}Test") {
    filter.excludeTestsMatching(cliPattern)
    environment("KOTEST_TAGS", "!Cli")
}
tasks.register<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeHostTest>("cliTest") {
    group = "verification"
    description = "Runs the Kotest CLI specs (rtok.cli.*) of :rtok."
    val unit = tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeHostTest>("${hostTarget}Test").get()
    dependsOn("linkDebugTest${hostTarget.replaceFirstChar(Char::uppercase)}")
    executableProperty.set(unit.executableProperty)
    environment("RTOK_FIXTURES", fixtures)
    targetName = unit.targetName
    filter.includeTestsMatching(cliPattern)
    environment("KOTEST_TAGS", "Cli")
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/cliTest"))
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/cliTest"))
    binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/cliTest/binary"))
}
tasks.named("check") { dependsOn("cliTest") }
