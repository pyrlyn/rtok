rootProject.name = "rtok-kotlin"
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
    // Shared build settings (docs/build-speed.md "Shared settings: ci-diffuser"): a sibling checkout of
    // the ci-diffuser repo. CI_DIFFUSER_DIR overrides the path (e.g. for worktrees outside apps/).
    val ciDiffuser = System.getenv("CI_DIFFUSER_DIR") ?: "../ci-diffuser"
    includeBuild(ciDiffuser)
    includeBuild("$ciDiffuser/settings-plugin")
}
// The WebAssembly interface and its Chasm adapter, shared with Octo: a checkout of kotlin-wasm
// (github.com/pyrlyn/kotlin-wasm). KOTLIN_WASM_DIR overrides the path, as CI_DIFFUSER_DIR does above.
includeBuild(System.getenv("KOTLIN_WASM_DIR") ?: "../../packages/kotlin-wasm")
// Remote build cache on Cloudflare R2 (docs/build-speed.md). Approved by Ivan 2026-10-26.
// Repositories + R2 remote build cache (docs/build-speed.md), approved by Ivan 2026-10-26; logic in ci.diffuser.settings.
plugins { id("com.github.burrunan.s3-build-cache") version "1.9.10"; id("ci.diffuser.settings") }
include(":humantime", ":treesitter", ":treesitter-native", ":toml", ":toml-ktoml", ":db", ":db-room", ":rtok-plugin-sdk", ":rtok-config", ":rtok-log", ":rtok-hook", ":rtok-store", ":rtok-sys", ":rtok-mcp", ":rtok-agent-sdk", ":rtok", ":rtok-wasm", ":rtok-e2e", ":http", ":http-ktor", ":http-server", ":http-server-ktor", ":zstd", ":zstd-native", ":semver", ":toml-aot", ":yaml-aot")
listOf("humantime", "treesitter", "treesitter-native", "toml", "toml-ktoml", "db", "db-room", "http", "http-ktor", "http-server", "http-server-ktor", "zstd", "zstd-native", "semver", "toml-aot", "yaml-aot").forEach { project(":$it").projectDir = file("libs/$it") }

