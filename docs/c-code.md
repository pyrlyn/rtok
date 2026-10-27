# Native C dependencies

All native (non-Kotlin) code compiled into rtok is C. There is no C++, Rust, Objective-C or assembly in the build
(`rtok-store/src/nativeTest/fixtures/schema.rs` is a test fixture, not compiled). Branch `linux-spawn` (`ea65445`)
has the same C sources as `main` since #230.

## Third-party C libraries

Allowed for now, to move faster, when the Kotlin ecosystem has no equivalent.

| Library | Module | Why it is needed | Why it can't be dropped |
|---|---|---|---|
| tree-sitter runtime + vendored grammars | `libs/treesitter-native` (built with CMake; grammars from one tree-sitter-language-pack release asset pinned by sha256, plus the runtime and 4 grammars at pinned commits, all in `grammars.json`, managed by `scripts/grammars.py`) | Parses source into syntax trees for tags/symbols, locals and highlights | tree-sitter and the generated grammar `parser.c` files exist only in C; there is no Kotlin port |
| zstd decoder | `libs/zstd-native` (release tarball pinned with sha256 in `zstd.json`; only `lib/common` + `lib/decompress` compiled, no asm, no threads) | Decompresses zstd data | No zstd library for Kotlin/Native; writing our own decoder is slow and risky |
| SQLite | `libs/db-room` via `androidx.sqlite:sqlite-bundled` | The whole store, including FTS5 full-text search | SQLite is C; it ships prebuilt in the dependency, we don't compile it |
| libcurl (Linux only) | `libs/http-ktor` via `io.ktor:ktor-client-curl` | HTTP client on Linux | Ktor's only Kotlin/Native engine on Linux; macOS uses the Darwin engine (no C) |

## Our own C code

Policy: we don't write our own C without necessity. **This list must be updated whenever own C code is added or
removed.**

### `rtok-sys/src/nativeInterop/c/rtok_spawn.c` / `.h` (`cinterop/spawn.def`)

Functions (identical on `main` and `linux-spawn`):

- `rtok_spawn` — `posix_spawn(p)` with an ordered list of file actions (dup2/open/close/chdir), optional `setsid` and PATH search.
- `rtok_spawn_addchdir` — `posix_spawn_file_actions_addchdir_np`.
- `rtok_environ` — returns `environ`.
- `rtok_parent_of` — parent pid via `proc_pidinfo` (macOS; returns -1 elsewhere).
- `rtok_current_exe` — `_NSGetExecutablePath` (macOS; returns -1 elsewhere).
- `rtok_clone_or_copy` — copy-on-write clone (`clonefile` on macOS, `FICLONE` on Linux), else full copy.
- `rtok_free_bytes` — `statvfs` free bytes.
- `rtok_set_times` — `utimensat` access/modification time.

Why C: Kotlin/Native shared native code sees only `platform.posix`, while `posix_spawn`, `statvfs` and `utimensat`
on Linux live in `platform.linux`; `clonefile`/`proc_pidinfo` are macOS-only and `FICLONE` is Linux-only.

Replaceable by Kotlin/Native: yes — `expect`/`actual` per OS in `macosMain`/`linuxMain` using platform cinterop
directly. Cost: the spawn code is written twice. Times and free space are trivial.

### `libs/zstd-native/src/nativeInterop/c/rtok_zstd.c` / `.h`

`rtok_zstd_decode_all` / `rtok_zstd_free`: streaming decode of every frame into one buffer with a size cap.
Why C: the `ZSTD_decompressStream` loop was simpler to write next to the library.
Replaceable: yes, easily — the same loop in Kotlin over the zstd cinterop.

### `libs/treesitter-native/src/nativeInterop/c/rtok_ts.h`

`rtok_ts_grammars` / `rtok_ts_grammar_count`: table of grammars (name, `language()` pointer, tags/locals/highlights
queries). Why C: a bridge to grammars that are themselves C.
Replaceable: yes — list grammars in Kotlin via cinterop and keep queries as Kotlin resources.
