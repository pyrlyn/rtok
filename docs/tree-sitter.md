# tree-sitter

`libs/treesitter` (`:treesitter`) is rtok's own interface: `TreeSitter`, `Grammar`, `Tree`, `Node`,
`Query` (+ standard text predicates in `matches`) and `TagsConfiguration` (the `tree-sitter-tags`
semantics: `@name`, `@definition.*`/`@reference.*`, `@doc` + `#strip!`/`#select-adjacent!`, `@ignore`,
`#is-not? local` with `locals.scm` scopes, one tag per name range).

`libs/treesitter-native` (`:treesitter-native`) implements it with cinterop over `librtok_ts.a`:
the tree-sitter C runtime, every grammar and a generated table (`rtok_ts_table.c`) of
`{name, language(), tags.scm, locals.scm, highlights.scm}`. Kotlin code never names a grammar symbol.

## Sources
- Pins: `libs/treesitter-native/grammars.json`, fetched by `./gradlew :treesitter-native:fetchGrammars`
  (or `python3 scripts/grammars.py fetch`) into `build/vendor`; nothing downloaded is committed.
- `pack`: 30 grammars come from one release asset of
  [tree-sitter-language-pack](https://github.com/xberg-io/tree-sitter-language-pack/releases),
  `parser-sources-<version>.tar.zst`, pinned by version and sha256 (checked against the published `.sha256`).
  Only the listed grammars and only `src/*.c|*.h`, `src/tree_sitter/*.h`, `common/` and
  `queries/{tags,locals,highlights}.scm` are extracted. Grammar versions are chosen by the pack
  (`sources/language_definitions.json` in the asset lists repo + rev per grammar).
  Update: bump `version`, `asset`, `sha256` (from the release's `.sha256`).
- `repos`: fetched as GitHub tarballs at a pinned commit, keeping only the `keep` path prefixes:
  the runtime (`lib/src`, `lib/include`), the three grammars the pack lacks
  (`ocaml_type`, `php_only`, `ql_dbscheme`) and kotlin: the pack ships fwcd/tree-sitter-kotlin, a different
  grammar without fields that rtok's own `KOTLIN_TAGS` query does not compile against. Update with `python3 scripts/grammars.py lock`.
- Unpacking `.tar.zst` needs the `zstd` CLI on PATH (Homebrew / distro package; no mise package).
  Python stdlib gets zstd only in 3.14.
- Every grammar must be ABI >= 13 (the runtime's minimum); fetch fails otherwise. All pack grammars are
  ABI 14/15, so the old tree-sitter CLI regeneration step (fluent, ABI 8) is gone.
- Any change to `grammars.json` invalidates `build/vendor` (stamp `.pins` = sha256 of the pin file).
- `buildGrammars` runs CMake + Ninja (both from `mise.toml`); the static library is a cinterop input.

## Size and build time (macosArm64, 2026-10-26)
Code + data per grammar (`size` of the objects, MB): verilog 17.29, c_sharp 5.08, ocaml 4.58, swift 3.99,
ocaml_interface 3.88, scala 3.86, ocaml_type 3.77, haskell 3.68, kotlin 3.29, cpp 3.28, julia 2.52,
agda 2.06, ruby 2.01, tsx 1.38, typescript 1.35, bash 1.30, dart 1.15, rust 1.07, php 1.03, php_only 0.98,
c 0.60, python 0.43, java 0.40, javascript 0.39, ql 0.30, go 0.21, css 0.12, the rest < 0.05.
Total 70.1 MB of grammar code/data; `librtok_ts.a` is 74.6 MB.
Clean C build of all grammars: 4.7 s wall (23 s CPU, 16 cores, Ninja); fetch ~8 s once (37.4 MB pack + 5.2 MB of tarballs).
Release binary impact: see "rtok binary" below.
