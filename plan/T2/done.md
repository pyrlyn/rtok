# T2. Done

## What changed

- `settings.gradle.kts` includes kotlin-kit (`KOTLIN_KIT_DIR`, else `packages/kotlin-kit` in any directory above the
  checkout, the same lookup as kotlin-snapshot).
- Taken from kotlin-kit and deleted from `libs/`: `semver`, `toml-aot`, `yaml-aot` (identical sources), `toml`
  (`TomlReader.kt` identical; kit also has `TomlParser`/`TomlWriter`, unused here), `db` and `db-room` (kit adds
  `Cursor`/`Db.cursor`; nothing here implements `Db`, and `RoomDbDriver.query` runs the same statements).
- Imports moved from `rtok.toml` / `rtok.db` / `rtok.db.room` to `toml` / `db` / `db.room`.
- Docs: `docs/toolchain.md` package row, `docs/c-code.md` SQLite row, `docs/store.md`, `docs/toml.md`.

PR: https://github.com/pyrlyn/rtok/pull/255

## Kept in `libs/` until kotlin-kit has their fixes

kotlin-kit is another project (`pyrlyn/packages`); its changes need the creator's OK and their own PR there.

| Module | Here, not in kotlin-kit | In kotlin-kit, not here |
| --- | --- | --- |
| `toml-ktoml` | #246: escaped quotes in arrays of inline tables (`QuoteEscapes.kt`); dotted `[[a.b.c]]` nesting under plain tables | `key =` and `key = null` are errors |
| `http` | `HttpResponse.headers`, `HttpClient.request(method, …)`, `StreamingHttpClient` / `StreamedResponse` (`Streaming.kt`) | `put`, `patch`, `stream` with `HttpHead` / `HttpBody` |
| `http-ktor` | #247: streamed reads bounded by the socket timeout on Linux; a no-redirect client for the proxy; any method | WinHttp engine on Windows; `put` / `patch` / `stream` |

`http` needs an API merge in kotlin-kit (ketch and backend use kit's `stream`; rtok's proxy uses `StreamingHttpClient`),
so it is more than a copy. `toml-ktoml`'s kit side adds a stricter check that rtok would then pick up: a behaviour
change to review with the creator.

## `pyrlyn.kit:cli` and `pyrlyn.kit:fs` (not migrated)

- **cli**: rtok's Clikt use is one adapter (`rtok/cli/clikt/CliktAdapter.kt`) over `CommandSpec` (`rtok/cli/Spec.kt`).
  Mapping `CommandSpec` onto kit's `command {}` would replace the adapter (~150 lines) and could also replace rtok's own
  completion scripts and man pages (`rtok/completions/`, `rtok/man/Man.kt`). Gaps in kit: no option with an optional
  value (`num_args 0..=1`, used by `mcp`), no Clink completions, and `Matches` throws on an undeclared id where
  `Invocation` returns null. The switch changes help and error text (Clikt's → clap's), which no test pins byte for
  byte. Small, but not trivial: its own task.
- **fs**: kit works on `kotlinx.io.files.Path` with `FsException`; rtok-sys `Fs.kt` on `String` with `IoException`, and
  its writes truncate in place where kit's are atomic with fsync, `createDirAll` ignores errors where kit throws, and
  `removeDirAll` shells out to `rm -rf`. ~1100 imports in ~270 files. Moving call sites is large; delegating the bodies
  of `Fs.kt`'s write/remove/create functions to kit behind the current signatures is small but changes behaviour, so it
  needs the creator's decision. The path helpers (`normalize`, `ancestors`, `stripPrefix`, …) have no kit counterpart.

## `PORTING.md` rows to change (owned by T3)

- Line 31, semver: `libs/semver` (`:semver`) → `pyrlyn.kit:semver` (kotlin-kit).
- Line 72, toml: `libs/toml` interface → `pyrlyn.kit:toml` interface (kotlin-kit); `libs/toml-ktoml` stays.
- Line 73, SQLite: `libs/db` + `libs/db-room` → `pyrlyn.kit:db` + `pyrlyn.kit:db-room` (kotlin-kit).
- Lines 50, 51, 55: `:toml-aot` → `pyrlyn.kit:toml-aot`.

## Follow-ups

- One commit is named `fixup! T2: take toml from kotlin-kit` (a missed qualified `rtok.toml.TomlException`); history
  rewriting was not allowed in the session, so it stays for the squash merge.

## Checked

`./gradlew --continue macosArm64Test cliTest e2eTest` on macOS arm64: every test passed (`:rtok` 2365, `:rtok-store`
195, `:rtok-config` 91, `:toml-ktoml` 16, e2e 13 specs / 78 tests). Two tasks failed, neither from this change:

- `:rtok-sys:macosArm64Test`: `FsWatchTest.aWriteBelowTheRootIsDelivered` (FSEvents timing while other builds loaded
  the Mac); `:rtok-sys` depends on none of the switched modules, and a rerun passed.
- `:rtok-e2e:detekt`: `MagicNumber` at `GuardCheckE2eSpec.kt:61`, a file this branch does not touch (from #250, on
  `main`); T5 (detekt baselines) covers it.
