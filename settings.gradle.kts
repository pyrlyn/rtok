rootProject.name = "rtok-kotlin"
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
    // Shared builds live in the workspace's packages/. Every ancestor is searched, so the build works
    // from the main checkout and from a worktree nested deeper (e.g. .rtok/worktrees/); an env variable
    // points elsewhere. Defined here because pluginManagement cannot see the rest of the script.
    fun sharedBuild(name: String, env: String): String =
        System.getenv(env)?.let { settingsDir.resolve(it).path }
            ?: generateSequence(settingsDir.parentFile) { it.parentFile }
                .map { it.resolve("packages/$name") }
                .firstOrNull { it.isDirectory }
                ?.path
            ?: error("packages/$name not found above $settingsDir; set $env")

    // Shared build settings (ktlint, detekt, repositories, build cache).
    val sharedSettings = sharedBuild("shared-settings", "SHARED_SETTINGS_DIR")
    includeBuild(sharedSettings)
    includeBuild("$sharedSettings/settings-plugin")
    extra["kotlinWasmDir"] = sharedBuild("kotlin-wasm", "KOTLIN_WASM_DIR")
    extra["kotlinSnapshotDir"] = sharedBuild("kotlin-snapshot", "KOTLIN_SNAPSHOT_DIR")
    extra["kotlinWebglDir"] = sharedBuild("kotlin-webgl", "KOTLIN_WEBGL_DIR")
    extra["kotlinKitDir"] = sharedBuild("kotlin-kit", "KOTLIN_KIT_DIR")
}
// The WebAssembly interface and its Chasm adapter, shared with Octo: a checkout of kotlin-wasm
// (github.com/pyrlyn/packages, kotlin-wasm/).
includeBuild(extra["kotlinWasmDir"] as String)
// insta-style snapshot assertions for tests (docs/testing.md "Snapshot tests"): a checkout of
// kotlin-snapshot.
includeBuild(extra["kotlinSnapshotDir"] as String)
// WebGL2 wrapper, Compose host and the generic graph cloud for :rtok-web-compose.
includeBuild(extra["kotlinWebglDir"] as String)
// TOML, YAML, semver and SQLite modules shared with ketch and backend: a checkout of kotlin-kit
// (github.com/pyrlyn/packages, kotlin-kit/).
includeBuild(extra["kotlinKitDir"] as String)
// Remote build cache on Cloudflare R2 (docs/build-speed.md). Approved by Ivan 2026-10-26.
// Repositories + R2 remote build cache (docs/build-speed.md), approved by Ivan 2026-10-26; logic in shared.settings.
plugins { id("com.github.burrunan.s3-build-cache") version "1.9.10"; id("shared.settings") }
include(":humantime", ":treesitter", ":treesitter-native", ":toml-ktoml", ":rtok-plugin-sdk", ":rtok-config", ":rtok-log", ":rtok-hook", ":rtok-store", ":rtok-sys", ":rtok-mcp", ":rtok-agent-sdk", ":rtok", ":rtok-wasm", ":rtok-e2e", ":http", ":http-ktor", ":http-server", ":http-server-ktor", ":zstd", ":zstd-native", ":globset", ":json-strict", ":tui", ":rtok-web", ":rtok-web-model", ":rtok-web-ui", ":rtok-web-compose")
listOf("humantime", "treesitter", "treesitter-native", "toml-ktoml", "http", "http-ktor", "http-server", "http-server-ktor", "zstd", "zstd-native", "globset", "json-strict", "tui").forEach { project(":$it").projectDir = file("libs/$it") }

