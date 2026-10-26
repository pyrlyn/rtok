rootProject.name = "rtok-kotlin"
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
include(":rtok-plugin-sdk", ":rtok-config", ":rtok-log", ":rtok-hook", ":rtok-store", ":rtok-sys", ":rtok-mcp", ":rtok-agent-sdk", ":rtok")
