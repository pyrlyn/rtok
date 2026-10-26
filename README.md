# rtok-kotlin

Kotlin Multiplatform / Kotlin/Native port of [rtok](../rtok) (token-reduction CLI for AI coding agents).
**Work in progress** — see [PORTING.md](PORTING.md) for what is ported and [port-plan.md](port-plan.md) for the plan.

Excluded by design: the TUI (`rtok tui`) and web UI (`rtok web`, `rtok dashboard`).

Build & test (macOS arm64): `./gradlew macosArm64Test` · binary: `./gradlew :rtok:linkReleaseExecutableMacosArm64`
→ `rtok/build/bin/macosArm64/releaseExecutable/rtok.kexe`. Other targets: `-Prtok.allTargets=true`.

Docs: [build speed](docs/build-speed.md) · [token estimator](docs/tokens.md) · [modes](docs/modes.md) · [log](docs/log.md) · [project root](docs/config-root.md) · [hook protocol](docs/hook-protocol.md) · [build speed](docs/build-speed.md).

- [Configuration](docs/config.md)
- [Hooks](docs/hooks.md)
- [Store](docs/store.md)
- [Plugin runtime](docs/plugin-runtime.md)
- [archive plugin](docs/archive.md)
- [API docs (Dokka)](docs/api-docs.md)
- [Testing (kotlin.test, Kotest, e2e)](docs/testing.md)
