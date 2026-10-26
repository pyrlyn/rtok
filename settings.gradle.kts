rootProject.name = "rtok-kotlin"
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
    // Shared build settings (docs/build-speed.md "Shared settings: ci-diffuser"): a sibling checkout of
    // the ci-diffuser repo. CI_DIFFUSER_DIR overrides the path (e.g. for worktrees outside apps/).
    val ciDiffuser = System.getenv("CI_DIFFUSER_DIR") ?: "../ci-diffuser"
    includeBuild(ciDiffuser)
    includeBuild("$ciDiffuser/settings-plugin")
}
// Remote build cache on Cloudflare R2 (docs/build-speed.md). Approved by Ivan 2026-10-26.
// Repositories + R2 remote build cache (docs/build-speed.md), approved by Ivan 2026-10-26; logic in ci.diffuser.settings.
plugins { id("com.github.burrunan.s3-build-cache") version "1.9.10"; id("ci.diffuser.settings") }
include(":humantime", ":treesitter", ":treesitter-native", ":toml", ":toml-ktoml", ":db", ":db-room", ":rtok-plugin-sdk", ":rtok-config", ":rtok-log", ":rtok-hook", ":rtok-store", ":rtok-sys", ":rtok-mcp", ":rtok-agent-sdk", ":rtok", ":rtok-wasm", ":rtok-e2e", ":http", ":http-ktor", ":wasm", ":wasm-chasm")
listOf("humantime", "treesitter", "treesitter-native", "toml", "toml-ktoml", "db", "db-room", "http", "http-ktor", "wasm", "wasm-chasm").forEach { project(":$it").projectDir = file("libs/$it") }

