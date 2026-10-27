import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
// rules/default.toml is copied verbatim from the Rust repo's rules/default.toml (the built-in cmd rules).
val defaultRules = embedText("genDefaultRules", "rules/default.toml", "rtok.plugins.cmd", "DEFAULT_RULES", "DefaultRules")
configure<KotlinMultiplatformExtension> {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            entryPoint = "rtok.main"
            baseName = "rtok"
        }
    }
    sourceSets.commonMain
        .get()
        .kotlin
        .srcDir(defaultRules)
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
        implementation(project(":rtok-wasm"))
        implementation("pyrlyn.wasm:wasm-chasm")
        implementation(project(":treesitter-native"))
        implementation(project(":zstd-native"))
        implementation("com.github.ajalt.clikt:clikt:5.1.0")
    }
}
val fixtures =
    layout.projectDirectory
        .dir("src/commonTest/fixtures")
        .asFile.absolutePath
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("RTOK_FIXTURES", fixtures)
    environment("RTOK_WASM_FIXTURES", rootDir.resolve("rtok-wasm/src/nativeTest/fixtures").absolutePath)
}

// Test groups (docs/testing.md): `macosArm64Test` = unit tests without the CLI specs, `cliTest` = the
// Kotest CLI specs (package rtok.cli, tagged `Cli`; Kotest ignores Gradle's filter, so KOTEST_TAGS selects them) from the same test binary; e2e lives in :rtok-e2e (`e2eTest`).
val cliPattern = "rtok.cli.*"
tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeHostTest>("macosArm64Test") {
    filter.excludeTestsMatching(cliPattern)
    environment("KOTEST_TAGS", "!Cli")
}
tasks.register<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeHostTest>("cliTest") {
    group = "verification"
    description = "Runs the Kotest CLI specs (rtok.cli.*) of :rtok."
    val unit = tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeHostTest>("macosArm64Test").get()
    dependsOn("linkDebugTestMacosArm64")
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
