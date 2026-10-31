# Release binary size

Measured 2026-10-30 on Apple Silicon (macosArm64), Kotlin/Native 2.4.21, Gradle 9.8.2, Xcode ld 27037.1, tree
`2d970f5` (main after #251). The shipped release link, `./gradlew :rtok:linkReleaseExecutableMacosArm64`
(`mise run release`), was **108,768,408 bytes** when this was measured and is **77,245,112 bytes** after the two changes
in "Applied" below. The budget that guards it (79,000,000 bytes, +2.3%) is in `scripts/size_check.py`
(`mise run size-check`). Sections "Where the bytes are" to "What each release flag buys" describe the 108,768,408-byte
binary, the state before the changes.

## Where the bytes are

Two links were measured. The shipped one strips local symbols, so it cannot say which package a byte belongs to;
an unstripped one (`-Pdiffuser.releaseSize=none`, 140,036,776 bytes) plus a linker map can. The tree-sitter tables
and the embedded wasm are the same bytes in both (only their string encoding differs, below), so the shares carry
over.

Sections of the shipped binary (`size -m rtok/build/bin/macosArm64/releaseExecutable/rtok.kexe`):

| Segment, section | Bytes | What it is |
|---|---|---|
| `__TEXT,__const` | 71,087,008 | tree-sitter parse tables (the unstripped map attributes 70.9 MB of it to the grammars) |
| `__DATA_CONST,__const` | 20,031,856 | embedded web UI wasm as base64 strings (≈16.2 MB) + Kotlin type info and string literals |
| `__TEXT,__text` | 16,145,476 | all code: Kotlin, SQLite, tree-sitter C, zstd |
| the rest | ≈1.5 MB | cstring, unwind info, exception tables, `__LINKEDIT` |

Owners, from the linker map of the unstripped link (`python3 scripts/size_map.py --link`; bytes of symbols,
unstripped, so the wasm part of the Kotlin string row is 2× its shipped size):

| Owner | Code | Data | Total |
|---|---|---|---|
| tree-sitter: 34 grammars + runtime (`libs/treesitter-native`) | 2,461,712 | 71,143,022 | 73,604,734 |
| Kotlin string literals, type info, **embedded wasm blobs** | 232,668 | 34,922,321 | 35,154,989 |
| Kotlin `rtok.*` modules | 9,953,484 | 985,032 | 10,938,516 |
| Chasm, the wasm runtime for plugins (`io.github.charlietap`) | 3,715,936 | 720,400 | 4,436,336 |
| Kotlin stdlib, kotlinx.* | 1,775,184 | 363,872 | 2,139,056 |
| SQLite bundled by `androidx.sqlite` | 1,499,908 | 102,803 | 1,602,711 |
| ktor (server) | 1,259,124 | 291,828 | 1,550,952 |
| other Kotlin libraries (`com.*`, tui, toml/yaml AOT, globset, …) | ≈1.2 MB | | ≈1.4 MB |
| zstd | 64,092 | 4,772 | 68,864 |

Shipped shares, from the table above and the section sizes: tree-sitter ≈ 73 MB (**67%**), the embedded web UI
≈ 16.2 MB (**15%**), everything else (Kotlin code about 12 MB, SQLite, ktor, Chasm, runtime) ≈ 19 MB (**18%**).
zstd is 69 KB; SQLite 1.6 MB.

### Tree-sitter: 34 grammars, 14 of them for tags

`rtok/src/nativeMain/kotlin/rtok/plugins/read/Languages.kt` registers 14 grammars that produce tags (the same 14
as the Rust rtok: rust, typescript, tsx, javascript, python, dart, c, go, java, kotlin, swift, c_sharp, ruby, php)
and 20 "parse-only" grammars used by stripped mode node walks.

| Group | Grammars | Bytes (map) |
|---|---|---|
| tagged | 14 | 23,380,515 |
| parse-only | 20 | 49,854,547 |
| runtime, scanners, table glue | | ≈0.4 |

Largest: verilog 18,126,893 (16,635,680 is one `ts_parse_table`), c_sharp 5,326,064, ocaml 4,799,915, swift
4,170,704, ocaml_interface 4,052,084, scala 4,031,626, ocaml_type 3,936,311, haskell 3,790,406, kotlin 3,437,705,
cpp 3,429,425, julia 2,635,627, agda 2,155,136, ruby 2,096,106. The 8 smallest add up to 0.22 MB.

### Compose and web modules

No Compose code is linked into the CLI: the map has no `androidx.compose` symbol and no `kotlinx.html`; `rtok.web`
is about 0.25 MB. What is in the binary is the **built output** of `:rtok-web-compose` (wasmJs): `embedDir`
(`buildSrc/src/main/kotlin/EmbedDir.kt`) stores binary assets as base64 string chunks, and `docs/web.md` already
notes this. Decoding the 1,969 chunks of 8,192 characters found in the binary gives two modules:

| Module | Bytes | gzip -9 | zstd -19 |
|---|---|---|---|
| emscripten GL imports (`emscripten_glUniform1fv`, …), presumably Skiko | 8,644,619 | 3,325,474 | 2,789,187 |
| Kotlin/Wasm Compose app (`kotlin.wasm.internal.*`) | 3,201,024 | 1,035,648 | 841,412 |
| total | 11,845,643 | 4,361,122 | 3,630,599 |

As base64 they fill those 1,969 chunks (16.1 M characters). With `latin1Strings` each character is one byte (16.1 MB of data); without it
Kotlin/Native stores them as UTF-16 (32 MB). That is why `latin1` is the largest single flag below.

## Comparison with the Rust rtok

`CARGO_TARGET_DIR=<scratch> cargo build --release` in `../rtok` at `3163315`, cargo 1.99.1, default features, profile
`release` with `strip = "symbols"` (the repo's own `Cargo.toml`); nothing in `../rtok` was changed.

| | Kotlin rtok | Rust rtok |
|---|---|---|
| File | 108,768,408 | 46,088,256 |
| code (`__text`) | 16.1 MB | 28.5 MB |
| constant data | 91.1 MB | 10.9 MB |
| gzip -9 | 18,609,284 | 16,683,266 |
| zstd -19 | 14,189,316 | 12,799,558 |
| tree-sitter grammars | 34 | 13 (the lang-* features) |
| embedded web UI wasm | yes, 11.8 MB raw | not in the default-feature binary |

The Kotlin binary has less code and 8× the constant data. Compressed it is only 12% larger (gzip) because parse
tables and base64 text compress well; what a release download costs is therefore much closer than the file size.

## What each release flag buys

`diffuser.releaseSize` in `gradle.properties` (default `all`; applied by shared-settings `NativeConventionPlugin`,
release executables only) selects `small`, `strip`, `latin1`. Each alone, on the same tree, `none` = 140,036,776:

| `-Pdiffuser.releaseSize=` | Binary | Δ vs none | Flag |
|---|---|---|---|
| `none` | 140,036,776 | | |
| `small` | 133,919,752 | −6,117,024 | `-Xbinary=smallBinary=true` |
| `strip` | 131,838,808 | −8,197,968 | `-Wl,-dead_strip,-x,-S` |
| `latin1` | 122,910,616 | −17,126,160 | `-Xbinary=latin1Strings=true` |
| `all` | 108,768,408 | −31,268,368 | all three |

Sources (checked 2026-10-30): [Kotlin/Native binary options](https://kotlinlang.org/docs/native-binary-options.html)
for `smallBinary` (experimental since Kotlin 2.2.20, "decreases the binary size of release binaries") and
`latin1Strings` (experimental since 2.2.0, reduces binary size and changes memory use); that page names no other
option that shrinks a binary. `-dead_strip`, `-x`, `-S` are documented in `man ld` (ld 27037.1: remove unreachable
code and data; drop non-global symbols; drop debug info). The earlier per-flag table in `docs/build-speed.md` was
taken before grammars, ktor and the web UI were linked and its absolute sizes (87.6 MB for `all`) are out of date.

History for the shipped link: 17.9 MB (no grammars) → 9.9 MB with the flags (#8) → 87.6 MB (grammars, zstd, ktor,
Room) → 108.8 MB now (mostly the embedded web UI wasm, plus later additions).

## Applied

| Step | Binary | Δ | How it was measured |
|---|---|---|---|
| before | 108,768,408 | | `mise run release` |
| wasm modules as zstd -19 + base64 instead of raw base64 (`buildSrc` `EmbedDir.kt`, `rtok-web` `EmbeddedAssets.kt`) | 97,671,000 | −11,097,408 (−10.2%) | linked; `__DATA_CONST,__const` 20.0 → 9.0 MB |
| drop the `agda` and `verilog` grammars (`grammars.json`, `Languages.kt`) | 77,245,112 | −20,425,888 (−20.9%) | linked; map predicted 20.3 MB |
| total | 77,245,112 | −31,523,296 (−29.0%) | |

The wasm change predicted −12.6 MB (the modules compressed, without the base64 text around them); the real saving is
1.5 MB smaller because the compressed frame is still stored as base64 (3.6 MB → 4.8 MB, ×4/3). The text constant is
latin1 in the release binary (one byte per character). Behaviour: the browser receives the same wasm bytes (decoded
once on first request, 64 MiB cap, tests in `EmbeddedAssetsTest`); `read mode=stripped` for `.v`, `.sv` and `.agda`
falls back to `full`, like any language without a grammar. Compile-time dependency added: `com.github.luben:zstd-jni`
1.5.7-25 in `buildSrc` (latest on Maven Central 2026-10-30, release 2026-10-28), used only to compress.

`scripts/grammars.py fetch` now also removes `build/vendor` directories that are no longer pinned, since the grammar
list is whatever the vendor tree contains.

Tests after both changes: `./gradlew macosArm64Test cliTest e2eTest` green, run without `:rtok-e2e:detekt` (which fails on
a magic number in `GuardCheckE2eSpec.kt`, a file this task does not touch) and without `RTOK_AGENT_ID` set (an agent
session id in the environment fails `AgentsCliSpec`).

## Remaining proposals

| # | Proposal | Effect | Behaviour | Measured how |
|---|---|---|---|---|
| 1 | Also drop the other large parse-only grammars: ocaml ×3, scala, haskell, julia | −23.3 MB | those suffixes lose stripped mode | linker map attribution; not linked |
| 2 | Move all remaining parse-only grammars out of the binary (load on demand as plugins) | up to −29.6 MB | needs a loading design | linker map attribution |
| 3 | Store the wasm frame as raw bytes (a C array) instead of base64 text | about −1.2 MB | none | arithmetic: 4.8 MB → 3.6 MB |
| 4 | Ship the release compressed | download about 10-14 MB (zstd) | none | the 108 MB binary compressed to 14.2 MB (zstd -19), 18.6 MB (gzip -9); not re-measured on the new binary |

Proposal 2 alone would bring the binary to about 47.7 MB, next to the Rust binary's 46 MB.

## Commands

```bash
# shipped binary and its sections
./gradlew :rtok:linkReleaseExecutableMacosArm64
stat -f %z rtok/build/bin/macosArm64/releaseExecutable/rtok.kexe
size -m rtok/build/bin/macosArm64/releaseExecutable/rtok.kexe

# where the bytes belong: unstripped link with an ld64 linker map, then the report
python3 scripts/size_map.py --link          # writes rtok/build/size.map
python3 scripts/size_map.py --top 40

# one flag at a time
./gradlew :rtok:linkReleaseExecutableMacosArm64 -Pdiffuser.releaseSize=small    # or strip, latin1, none

# budget
mise run size-check                          # python3 scripts/size_check.py

# Rust comparison (measure only)
CARGO_TARGET_DIR=/some/scratch cargo build --release   # in ../rtok
```

`-Prtok.linkMap=<file>` (in `rtok/build.gradle.kts`) passes `-Wl,-map,<file>` to the link; it is ignored unless set.
The wasm modules were decoded from the unstripped binary: take the 16,416-byte `__DATA_CONST,__const` symbols that
the map lists under the Kotlin object, strip the 16-byte header of each, join the UTF-16LE text and base64-decode it.
Link times on this Mac were 1.5-3.3 minutes while other builds ran, so they are not comparable; sizes are exact.
