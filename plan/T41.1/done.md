**T41.1 Dart in the LSP `graph` backend** · T30.2 · `src/plugins/graph/lsp.rs`, `tests/graph_lsp_gate.rs`
Do: a `pubspec.yaml` root picks `dart language-server` (Dart SDK on PATH); `workspace_of` stops at `pubspec.yaml`; `.dart` files open as `languageId = "dart"`.
Check: unit test for `pick`/`workspace_of` on a `pubspec.yaml` root; `graph_lsp_gate`-style test skipping when `dart` is not on PATH, else `outline` of a two-symbol `lib/main.dart`; `just check`.
Complexity: 2/5 — one backend match arm, one languageId, one root probe; no new dep, no schema, contract untouched.
Status: done 2026-09-15
Check result: `cargo test --lib plugins::graph::lsp` 2 passed (incl `dart_pubspec_root_picks_dart_language_server`); `cargo test --test graph_lsp_gate` 5 passed incl `lsp_backend_outlines_dart_main`, live on Dart 3.13.1 (not skipped); `cargo test --test graph_contract` 3 passed untouched; `cargo clippy --all-targets -- -D warnings` clean; `cargo fmt --check` clean; full `just check` exit 0 (`test --workspace`: 41 suites ok, lib 429 passed; build-min; jscpd under threshold). 2 files, no new dependency.
Model: OpenCode / Muse Spark 1.3 Contributor
