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
        implementation(project(":tui"))
        implementation(project(":rtok-wasm"))
        implementation("pyrlyn.wasm:wasm-chasm")
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

// Agent test group (docs/testing.md "Agent tests"): agent-install / host-app tests, i.e. package rtok.agents,
// rtok.model.AgentsTest and the CLI spec rtok.cli.AgentsCliSpec. Kotest specs among them carry the `Agents` tag.
// `agentTest` runs only them; `noAgentTest` runs the rest of the :rtok binary (unit + CLI). The default
// `macosArm64Test` and `cliTest` stay unchanged and still include the agent tests.
val agentPatterns = listOf("rtok.agents.*", "rtok.model.AgentsTest", "rtok.model.AgentsTest.*", "rtok.cli.AgentsCliSpec", "rtok.cli.AgentsCliSpec.*")

fun registerGroupTest(
    name: String,
    text: String,
    tags: String,
    configureFilter: org.gradle.api.tasks.testing.TestFilter.() -> Unit,
) = tasks.register<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeHostTest>(name) {
    group = "verification"
    description = text
    val unit = tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeHostTest>("macosArm64Test").get()
    dependsOn("linkDebugTestMacosArm64")
    executableProperty.set(unit.executableProperty)
    environment("RTOK_FIXTURES", fixtures)
    targetName = unit.targetName
    filter.configureFilter()
    environment("KOTEST_TAGS", tags)
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/$name"))
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/$name"))
    binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/$name/binary"))
}
registerGroupTest("agentTest", "Runs only the agent tests of :rtok (default while working on agent code).", "Agents") {
    agentPatterns.forEach { includeTestsMatching(it) }
}
registerGroupTest("noAgentTest", "Runs the unit and CLI tests of :rtok except the agent tests.", "!Agents") {
    agentPatterns.forEach { excludeTestsMatching(it) }
}
