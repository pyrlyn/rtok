# Toolchain (mise)

Every tool rtok-kotlin needs that [mise](https://mise.jdx.dev) can provide is pinned in the repo-local
`mise.toml`. Kotlin libraries and klibs (Ktor, ktoml, Room KMP, Chasm, Kotest, ...) stay in Gradle and
come from Maven Central. The build system is Gradle.

## Enable
```sh
mise install          # installs the pinned JDK, Kotlin, Gradle, Rust, cmake, ninja, tree-sitter CLI
./gradlew build       # with mise activated in the shell, or: mise exec -- ./gradlew build
```

| Tool | Why |
|---|---|
| java (Temurin LTS) | runs Gradle and the Kotlin daemon |
| kotlin | `kotlin` CLI for the `.main.kts` CI scripts; same version as the Gradle plugin |
| gradle | matches `gradle/wrapper/gradle-wrapper.properties` (the wrapper stays the entry point) |
| rust | builds the Rust wasm demo guest used by WASM plugin tests |
| cmake, ninja | native C builds (tree-sitter grammars, zstd) |
| tree-sitter | CLI to regenerate a grammar's `parser.c` when needed |

Not in mise: zstd has no mise package; its C sources are built by Gradle cinterop.

## Update
Bump the version in `mise.toml`; for Gradle also run `./gradlew wrapper --gradle-version <v>`.
The global `~/.config/mise/config.toml` is never touched.

## Disable
Without mise, install the same tools by hand; Gradle picks up `JAVA_HOME`.
