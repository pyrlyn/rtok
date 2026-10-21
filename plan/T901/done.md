The test fails on Windows dart 3.9 with `File is not being analyzed` (surfaced by the first full local `just check` on Windows, T272–T274, `docs/windows.md`); CI never runs it because the runners have no dart. Done = the test no longer fails on Windows dart 3.9 (project gets a `dart pub get` / readiness wait, or skips on that error with the reason in a comment) and a test pins the skip/ready predicate.

Check: `lsp_backend_outlines_dart_main` passes or skips with a stated reason on Windows dart 3.9; the predicate test passes on every OS; `just check`.

Plan: make `lsp_backend_outlines_dart_main` robust on Windows dart 3.9: write a minimal `pubspec.yaml` in the temp project and run `dart pub get`, or skip when the server answers `File is not being analyzed`, with the reason in a comment. CI has no dart, so the proof is a unit test of the skip/ready predicate that runs everywhere.

Outcome: `lsp_backend_outlines_dart_main` skips on Windows when the dart server answers `File is not being analyzed` (reason in a comment); the predicate `dart_not_analyzing` is pinned by `dart_skip_is_windows_only_and_names_the_exact_answer`, which runs on every OS. CI has no dart, so the skip itself is unexercised there.
