rootProject.name = "rtok-kotlin"
pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
dependencyResolutionManagement { repositories { mavenCentral(); google() } }
include(":humantime", ":toml", ":toml-ktoml", ":db", ":db-room", ":rtok-plugin-sdk", ":rtok-config", ":rtok-log", ":rtok-hook", ":rtok-store", ":rtok-sys", ":rtok-mcp", ":rtok-agent-sdk", ":rtok", ":rtok-wasm", ":rtok-e2e", ":http", ":http-ktor")
listOf("humantime", "toml", "toml-ktoml", "db", "db-room", "http", "http-ktor").forEach { project(":$it").projectDir = file("libs/$it") }
