// End-to-end CLI tests: Kotest specs that run the built debug `rtok.kexe` as a subprocess, in temp
// dirs with real git repositories. Not part of `macosArm64Test` (the binary link is slow);
// run with `./gradlew e2eTest` (docs/testing.md).
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

configure<KotlinMultiplatformExtension> {
    sourceSets.commonTest.dependencies {
        implementation(project(":rtok-sys"))
        // The hook-resident check connects to the resident's socket, as tests/demon.rs does through rtok_hook.
        implementation(project(":rtok-hook"))
        // The task specs register agents straight in the sandbox store, as tests/task_cli.rs does.
        implementation(project(":rtok-store"))
        implementation(project(":db-room"))
    }
}

// `-Pe2e.release` runs the suite against the release binary: some K/N bugs only show with release optimizations.
// `-Prtok.e2e.bin=<path>` runs it against any other binary (e.g. a fast-release build); it wins over `-Pe2e.release`.
val e2eRelease = providers.gradleProperty("e2e.release").isPresent
val rtokKind = if (e2eRelease) "release" else "debug"
val customBin = providers.gradleProperty("rtok.e2e.bin")
// `-Prtok.test.target=linuxArm64|linuxX64` (with `-Prtok.allTargets=true`) links the suite and the binary for that target
// instead of the macOS host; the task then runs them on the Gradle host, so use it on a host of that target or
// only link here and run scripts/lima-test.sh (docs/testing.md "Linux").
val target =
    providers.gradleProperty("rtok.test.target").getOrElse(
        if (org.jetbrains.kotlin.konan.target.HostManager.host == org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_X64) "linuxX64" else "macosArm64",
    )
val cap = target.replaceFirstChar(Char::uppercase)
val rtokBin =
    customBin
        .map { layout.projectDirectory.file(it) }
        .orElse(project(":rtok").layout.buildDirectory.file("bin/$target/${rtokKind}Executable/rtok.kexe"))
val testBin = layout.buildDirectory.file("bin/$target/debugTest/test.kexe")

// The fast default suite skips this module.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach { enabled = false }

tasks.register<Exec>("e2eTest") {
    group = "verification"
    description = "Runs the end-to-end CLI suite against the debug rtok binary."
    dependsOn("linkDebugTest$cap")
    if (!customBin.isPresent) dependsOn(if (e2eRelease) ":rtok:linkReleaseExecutable$cap" else ":rtok:linkDebugExecutable$cap")
    inputs.file(rtokBin)
    inputs.file(testBin)
    executable(testBin.get().asFile)
    environment("RTOK_BIN", rtokBin.get().asFile.absolutePath)
    // Isolated home for the binary under test; the suite never touches the real ~/.rtok.
    val e2eHome =
        layout.buildDirectory
            .dir("e2e/home")
            .get()
            .asFile
    environment("HOME", e2eHome.absolutePath)
    environment("RTOK_HOME", e2eHome.resolve(".rtok").absolutePath)
    val strayTilde =
        rootProject.layout.projectDirectory.asFile
            .walkTopDown()
            .maxDepth(2)
            .filter { it.name == "~" }
    doFirst { e2eHome.resolve(".rtok").mkdirs() }
    outputs.upToDateWhen { false }
    // The Kotest native launcher exits 0 even when a spec fails, so the verdict is read from its report.
    val log = layout.buildDirectory.file("e2e/output.txt")
    val logFile = log.get().asFile
    doFirst {
        logFile.parentFile.mkdirs()
        standardOutput =
            org.apache.tools.ant.util
                .TeeOutputStream(logFile.outputStream(), System.out)
    }
    doLast {
        val text = logFile.readText()
        val tests =
            Regex("""Tests:\s+\S*?(\d+) passed\S*, \S*?(\d+) failed""").find(text.replace(Regex("\u001B\\[[0-9;]*m"), ""))
                ?: throw GradleException("e2e: no Kotest summary in $logFile")
        if (tests.groupValues[2] != "0" || tests.groupValues[1] == "0") throw GradleException("e2e: ${tests.value} (see $logFile)")
        logger.lifecycle("e2e: ${tests.value}")
        strayTilde.firstOrNull()?.let { throw GradleException("e2e created a literal '~' directory: $it") }
    }
}
