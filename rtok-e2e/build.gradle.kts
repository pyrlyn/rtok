// End-to-end CLI tests: Kotest specs that run the built debug `rtok.kexe` as a subprocess, in temp
// dirs with real git repositories. Not part of `macosArm64Test` (the binary link is slow);
// run with `./gradlew e2eTest` (docs/testing.md).
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

configure<KotlinMultiplatformExtension> {
    sourceSets.commonTest.dependencies { implementation(project(":rtok-sys")) }
}

// `-Prtok.e2e.bin=<path>` runs the suite against another binary (e.g. a release or fast-release build).
val rtokBin = providers.gradleProperty("rtok.e2e.bin").map { layout.projectDirectory.file(it) }
    .orElse(project(":rtok").layout.buildDirectory.file("bin/macosArm64/debugExecutable/rtok.kexe"))
val testBin = layout.buildDirectory.file("bin/macosArm64/debugTest/test.kexe")

// The fast default suite skips this module.
tasks.named("macosArm64Test") { enabled = false }

tasks.register<Exec>("e2eTest") {
    group = "verification"; description = "Runs the end-to-end CLI suite against the debug rtok binary."
    dependsOn(":rtok:linkDebugExecutableMacosArm64", "linkDebugTestMacosArm64")
    inputs.file(rtokBin); inputs.file(testBin)
    executable(testBin.get().asFile)
    environment("RTOK_BIN", rtokBin.get().asFile.absolutePath)
    outputs.upToDateWhen { false }
    // The Kotest native launcher exits 0 even when a spec fails, so the verdict is read from its report.
    val log = layout.buildDirectory.file("e2e/output.txt")
    val logFile = log.get().asFile
    doFirst { logFile.parentFile.mkdirs(); standardOutput = org.apache.tools.ant.util.TeeOutputStream(logFile.outputStream(), System.out) }
    doLast {
        val text = logFile.readText()
        val tests = Regex("""Tests:\s+\S*?(\d+) passed\S*, \S*?(\d+) failed""").find(text.replace(Regex("\u001B\\[[0-9;]*m"), ""))
            ?: throw GradleException("e2e: no Kotest summary in ${logFile}")
        if (tests.groupValues[2] != "0" || tests.groupValues[1] == "0") throw GradleException("e2e: ${tests.value} (see $logFile)")
        logger.lifecycle("e2e: ${tests.value}")
    }
}
