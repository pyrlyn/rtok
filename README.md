# rtok-kotlin

Kotlin Multiplatform / Kotlin/Native port of [rtok](../rtok), the
token-reduction CLI for AI coding agents.

**Work in progress**: see [PORTING.md](PORTING.md) for what is ported and [port-plan.md](port-plan.md)
for the plan. The TUI (`rtok tui`) is ported; the web UI (`rtok web`, `rtok dashboard`) is in
progress, see [docs/web.md](docs/web.md).

## Quick start

```bash
mise install                                        # JDK, Kotlin, Gradle, Rust, cmake, tree-sitter CLI
./gradlew macosArm64Test                            # build and test (macOS arm64)
./gradlew :rtok:linkReleaseExecutableMacosArm64     # -> rtok/build/bin/macosArm64/releaseExecutable/rtok.kexe
```

Other targets: `-Prtok.allTargets=true`. Toolchain details: [toolchain.md](toolchain.md).

## Documentation

All pages, grouped, are listed in [docs/README.md](docs/README.md). The main ones:

- [Configuration](docs/config.md) and [project root](docs/config-root.md)
- [Hooks](docs/hooks.md) and [host install (`rtok agents`)](docs/agents.md)
- [Plugin runtime](docs/plugin-runtime.md) and [WASM plugins](docs/wasm-plugins.md)
- [Testing](docs/testing.md) and [build speed](docs/build-speed.md)
