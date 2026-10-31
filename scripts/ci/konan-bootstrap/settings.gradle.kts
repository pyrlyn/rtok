// A throwaway Kotlin/Native build for CI: linking it makes Kotlin/Native download its clang and Linux sysroot into
// ~/.konan, which the main build's buildSrc CrossCc needs while it is still configuring (see .github/workflows/ci.main.kts).
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    // The Kotlin pin in mise.toml, passed in by the workflow, so the bootstrap fetches the same toolchain as the main build.
    plugins { kotlin("multiplatform") version (System.getenv("KOTLIN_VERSION") ?: error("set KOTLIN_VERSION")) }
}
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "konan-bootstrap"
