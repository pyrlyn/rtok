# rtok-kotlin

Kotlin Multiplatform / Kotlin/Native port of [rtok](../rtok) (token-reduction CLI for AI coding agents).
**Work in progress** — see [PORTING.md](PORTING.md) for what is ported and [port-plan.md](port-plan.md) for the plan.

Excluded by design: the TUI (`rtok tui`) and web UI (`rtok web`, `rtok dashboard`).

Toolchain: `mise install` (JDK, Kotlin, Gradle, Rust, cmake, tree-sitter CLI; see [docs/toolchain.md](docs/toolchain.md)).

Build & test (macOS arm64): `./gradlew macosArm64Test` · binary: `./gradlew :rtok:linkReleaseExecutableMacosArm64`
→ `rtok/build/bin/macosArm64/releaseExecutable/rtok.kexe`. Other targets: `-Prtok.allTargets=true`.

Docs: [build speed](docs/build-speed.md) · [token estimator](docs/tokens.md) · [modes](docs/modes.md) · [log](docs/log.md) · [project root](docs/config-root.md) · [hook protocol](docs/hook-protocol.md) · [toolchain](docs/toolchain.md).

- [Configuration](docs/config.md)
- [Hooks](docs/hooks.md)
- [Host install (`rtok agents`)](docs/agents.md)
- [Store](docs/store.md)
- [Plugin runtime](docs/plugin-runtime.md)
- [archive plugin](docs/archive.md)
- [toon plugin](docs/toon.md)
- [compress plugin](docs/compress.md)
- [checkpoint and inject plugins](docs/checkpoint-inject.md)
- [guard plugin](docs/guard.md)
- [cmd plugin](docs/cmd.md)
- [WASM plugins](docs/wasm-plugins.md)
- [docs plugin](docs/docs-plugin.md)
- [json_tree plugin](docs/json-tree.md)
- [API docs (Dokka)](docs/api-docs.md)
- [Testing (kotlin.test, Kotest, e2e)](docs/testing.md)
- [Native C dependencies](docs/c-code.md)
