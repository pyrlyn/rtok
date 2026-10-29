#!/usr/bin/env kotlin
@file:Repository("https://repo.maven.apache.org/maven2/")
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:4.0.0")

@file:Repository("https://bindings.krzeminski.it")
@file:DependsOn("actions:checkout:v7.0.1")
@file:DependsOn("actions:cache:v6.1.0")
@file:DependsOn("gradle:actions__setup-gradle:v6.4.1")
@file:DependsOn("jdx:mise-action:v5.2.4")

import io.github.typesafegithub.workflows.actions.actions.Cache
import io.github.typesafegithub.workflows.actions.actions.Checkout
import io.github.typesafegithub.workflows.actions.gradle.ActionsSetupGradle
import io.github.typesafegithub.workflows.actions.jdx.MiseAction_Untyped
import io.github.typesafegithub.workflows.domain.Mode
import io.github.typesafegithub.workflows.domain.Permission
import io.github.typesafegithub.workflows.domain.RunnerType
import io.github.typesafegithub.workflows.domain.RunnerType.Macos15
import io.github.typesafegithub.workflows.domain.RunnerType.UbuntuLatest
import io.github.typesafegithub.workflows.domain.triggers.PullRequest
import io.github.typesafegithub.workflows.domain.triggers.Push
import io.github.typesafegithub.workflows.dsl.JobBuilder
import io.github.typesafegithub.workflows.dsl.workflow
import io.github.typesafegithub.workflows.yaml.CheckoutActionVersionSource
import io.github.typesafegithub.workflows.yaml.DEFAULT_CONSISTENCY_CHECK_JOB_CONFIG
import io.github.typesafegithub.workflows.yaml.Preamble

// Shared builds from github.com/pyrlyn/packages, checked out by setUp; absolute, since the
// included kotlin-wasm build reads the same variables relative to its own directory.
// KOTLIN_KIT_DIR is read once settings.gradle.kts includes packages/kotlin-kit.
val sharedBuilds =
    mapOf(
        "SHARED_SETTINGS_DIR" to "\${{ github.workspace }}/packages/shared-settings",
        "KOTLIN_WASM_DIR" to "\${{ github.workspace }}/packages/kotlin-wasm",
        "KOTLIN_SNAPSHOT_DIR" to "\${{ github.workspace }}/packages/kotlin-snapshot",
        "KOTLIN_WEBGL_DIR" to "\${{ github.workspace }}/packages/kotlin-webgl",
        "KOTLIN_KIT_DIR" to "\${{ github.workspace }}/packages/kotlin-kit",
    )

fun JobBuilder<*>.setUp() {
    uses(action = Checkout())
    // Pinned release tag; bump when rtok-kotlin needs newer shared builds.
    uses(action = Checkout(repository = "pyrlyn/packages", ref = "0.0.1", path = "packages"))
    // Installs every tool in mise.toml (JDK, Kotlin, CMake, Ninja, ...), so CI and developers build with the same versions.
    uses(action = MiseAction_Untyped())
    uses(action = ActionsSetupGradle())
    // The Kotlin/Native toolchain (~/.konan) is not a Gradle cache; its version follows the Kotlin pin in mise.toml, its targets the bootstrap project.
    uses(
        action =
            Cache(
                path = listOf("~/.konan"),
                key = "konan-\${{ runner.os }}-\${{ hashFiles('mise.toml', 'scripts/ci/konan-bootstrap/**') }}",
                restoreKeys = listOf("konan-\${{ runner.os }}-"),
            ),
    )
}

fun jobFor(
    workflowBuilder: io.github.typesafegithub.workflows.dsl.WorkflowBuilder,
    id: String,
    runner: RunnerType,
    command: String,
    prepare: String? = null,
) = workflowBuilder.job(
    id = id,
    runsOn = runner,
    env = sharedBuilds,
    // Cold Kotlin/Native builds are slow; the default of 6 hours only hides a hang.
    timeoutMinutes = 120,
) {
    setUp()
    if (prepare != null) run(name = "Prepare the Kotlin/Native toolchain", command = prepare)
    run(command = command)
}

workflow(
    name = "CI",
    on = listOf(Push(branches = listOf("main")), PullRequest()),
    permissions = mapOf(Permission.Contents to Mode.Read),
    sourceFile = __FILE__,
    targetFileName = "ci.yml",
    // Ahead of the library's own note, so nobody edits the YAML by hand.
    preamble = Preamble.WithOriginalAfter("DO NOT EDIT: generated from ci.main.kts; edit that file and run it to regenerate."),
    consistencyCheckJobConfig =
        DEFAULT_CONSISTENCY_CHECK_JOB_CONFIG.copy(
            // The library's bundled default is older than the checkout the build pins.
            checkoutActionVersion = CheckoutActionVersionSource.InferFromClasspath(),
        ),
) {
    // macos-15 is Apple Silicon; Intel macOS is not supported.
    jobFor(this, "macos", Macos15, "./gradlew macosArm64Test cliTest e2eTest --console=plain")
    // On a Linux x64 host cliTest and e2eTest pick linuxX64; allTargets adds the linux targets to the build.
    // Lint stays off here: the test tasks depend on ktlintCheck and detekt of every source set, macOS ones included, and
    // those pull in the macOS C compile (xcrun), which a Linux host cannot run. The macOS job lints.
    // buildSrc CrossCc needs Kotlin/Native's clang and sysroot in ~/.konan while the build is still configuring, and
    // only linking a Kotlin/Native target downloads them, so a throwaway project does that first (a no-op once cached).
    jobFor(
        this,
        "linux",
        UbuntuLatest,
        "./gradlew -Prtok.allTargets=true linuxX64Test cliTest e2eTest -x ktlintCheck -x detekt --console=plain",
        prepare = "KOTLIN_VERSION=\$(mise current kotlin) ./gradlew -p scripts/ci/konan-bootstrap linkDebugExecutableLinuxX64 linkDebugExecutableLinuxArm64 --console=plain",
    )
}
