rootProject.name = "rtok-kotlin"
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
include(":humantime", ":rtok-plugin-sdk", ":rtok-config", ":rtok-log", ":rtok-hook", ":rtok-store", ":rtok-sys", ":rtok-mcp", ":rtok-agent-sdk", ":rtok")
project(":humantime").projectDir = file("libs/humantime")
