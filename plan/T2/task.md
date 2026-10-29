---
status: in progress
priority: P1
complexity: 4
readiness: 10%
agent: Claude Code / claude-opus-5-5
---

# T2. Use kotlin-kit instead of libs/ copies

`packages/kotlin-kit` (group `pyrlyn.kit`) started as this project's `libs/`, but rtok-kotlin never switched
to it, so the same modules live twice and fixes land in one copy only (#246 reached `libs/toml-ktoml` alone).
That breaks the workspace rule against duplicated code.

Done: every `libs/` module that has a kotlin-kit counterpart with the same behaviour and API is replaced by
`pyrlyn.kit:<module>` from an included kotlin-kit build and deleted here; modules whose local copy has fixes or
API that kotlin-kit lacks stay until those land in kotlin-kit (its own PR), and `done.md` lists them; rtok's
behaviour does not change; `./gradlew macosArm64Test cliTest e2eTest` is green.

## Findings (source diff with `rtok.<pkg>` normalised to `<pkg>`)

| Module | kotlin-kit package | Diff | Action |
| --- | --- | --- | --- |
| `semver` | `semver` | identical | switch |
| `toml-aot` | `tomlaot` | identical | switch |
| `yaml-aot` | `yamlaot` | identical | switch |
| `toml` | `toml` (was `rtok.toml`) | `TomlReader.kt` identical; kit adds `TomlParser`, `TomlWriter` and tests | switch |
| `db` | `db` (was `rtok.db`) | kit adds `Cursor` and `Db.cursor`; nothing here implements `Db` | switch with `db-room` |
| `db-room` | `db.room` (was `rtok.db.room`) | kit: code in `unixMain`, `query` over `cursor`, tests via `db-testing`; same behaviour | switch |
| `toml-ktoml` | `toml.ktoml` | local has #246 (escaped quotes in arrays of inline tables) and the dotted `[[a.b.c]]` fix; kit has the `key =` / `null` error | keep until kotlin-kit has both fixes |
| `http` | `http` | local: `HttpResponse.headers`, `request(method, …)`, `StreamingHttpClient`/`StreamedResponse`; kit: `put`/`patch`, `stream` with `HttpHead`/`HttpBody` | keep until the APIs are merged in kotlin-kit |
| `http-ktor` | `http.ktor` | local: socket-timeout watchdog on Linux (#247), a no-redirect client for the proxy, `request`; kit: WinHttp, `put`/`patch`, `stream` | keep with `http` |

## Execution plan

1. `settings.gradle.kts`: `extra["kotlinKitDir"] = sharedBuild("kotlin-kit", "KOTLIN_KIT_DIR")` and
   `includeBuild`, like `kotlin-snapshot`.
2. One commit per switch (`semver`, `toml-aot`, `yaml-aot`, `toml`, `db` + `db-room`): dependency to
   `pyrlyn.kit:<module>`, imports `rtok.<pkg>` → `<pkg>`, drop the project from `settings.gradle.kts`, delete
   `libs/<module>`.
3. Compare `pyrlyn.kit:cli` with the Clikt adapter and `rtok/.../cli/Spec.kt`, and `pyrlyn.kit:fs` with
   `rtok-sys/.../Fs.kt`; write up the cost in `done.md`.
4. `docs/toolchain.md` package rows; `docs/c-code.md` if a C library moves.
5. Verify with `mise run test-smart` per step and the full `./gradlew macosArm64Test cliTest e2eTest` before
   the PR; write `done.md` with the `PORTING.md` rows that need changing.
