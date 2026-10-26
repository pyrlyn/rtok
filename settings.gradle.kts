rootProject.name = "rtok-kotlin"
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
include(":humantime", ":toml", ":toml-ktoml", ":rtok-plugin-sdk", ":rtok-config", ":rtok-log", ":rtok-hook", ":rtok-store", ":rtok-sys", ":rtok-mcp", ":rtok-agent-sdk", ":rtok")
project(":humantime").projectDir = file("libs/humantime")
project(":toml").projectDir = file("libs/toml")
project(":toml-ktoml").projectDir = file("libs/toml-ktoml")
