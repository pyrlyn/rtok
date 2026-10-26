# tree-sitter

`libs/treesitter` (`:treesitter`) is rtok's own interface: `TreeSitter`, `Grammar`, `Tree`, `Node`,
`Query` (+ standard text predicates in `matches`) and `TagsConfiguration` (the `tree-sitter-tags`
semantics: `@name`, `@definition.*`/`@reference.*`, `@doc` + `#strip!`/`#select-adjacent!`, `@ignore`,
`#is-not? local` with `locals.scm` scopes, one tag per name range).

`libs/treesitter-native` (`:treesitter-native`) implements it with cinterop over `librtok_ts.a`:
the tree-sitter C runtime, every grammar and a generated table (`rtok_ts_table.c`) of
`{name, language(), tags.scm, locals.scm, highlights.scm}`. Kotlin code never names a grammar symbol.

## Sources
- Pins: `libs/treesitter-native/grammars.json` (repo, ref, commit). Update with `python3 scripts/grammars.py lock`.
- `./gradlew :treesitter-native:fetchGrammars` (or `scripts/grammars.py fetch`) downloads the pinned tarballs
  into `build/vendor`, keeping only `src/`, `queries/`, `common/`; nothing generated is committed.
- Grammars below ABI 13 are regenerated with the tree-sitter CLI from `mise.toml` (today: fluent, ABI 8).
- `buildGrammars` runs CMake + Ninja (both from `mise.toml`); the static library is a cinterop input.

## Grammars (34 parsers from 31 repos)
Runtime: tree-sitter v0.27.1. Official org (`github.com/tree-sitter`, every grammar repo; `tree-sitter-graph`
is a tool, not a grammar): agda, bash, c, c-sharp, cpp, css, embedded-template, fluent, go, haskell, html,
java, javascript, jsdoc, json, julia, ocaml (ocaml, ocaml_interface, ocaml_type), php (php, php_only),
python, ql, ql-dbscheme, regex, ruby, rust, scala, typescript (typescript, tsx), verilog.
Third party: tree-sitter-grammars/tree-sitter-kotlin v1.1.0, UserNobody14/tree-sitter-dart (master),
alex-pinkus/tree-sitter-swift (`with-generated-files`, 0.7.x).

## Size and build time (macosArm64, 2026-10-26)
Code + data per grammar (`size` of the objects, MB): verilog 17.29, c_sharp 5.08, ocaml 4.58, swift 3.99,
ocaml_interface 3.88, scala 3.86, ocaml_type 3.77, haskell 3.68, kotlin 3.29, cpp 3.28, julia 2.52,
agda 2.06, ruby 2.01, tsx 1.38, typescript 1.35, bash 1.30, dart 1.15, rust 1.07, php 1.03, php_only 0.98,
c 0.60, python 0.43, java 0.40, javascript 0.39, ql 0.30, go 0.21, css 0.12, the rest < 0.05.
Total 70.1 MB of grammar code/data; `librtok_ts.a` is 74.6 MB.
Clean C build of all grammars: 4.7 s wall (23 s CPU, 16 cores, Ninja); fetch of all tarballs ~45 s once.
Release binary impact: see "rtok binary" below.
