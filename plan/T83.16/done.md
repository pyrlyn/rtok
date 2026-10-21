From run 36047996475's `windows` job (PR #352, 2026-09-24). `search` and `tree` print paths through `Path::display`, so Windows shows `src\main.rs` and the test, like any agent pasting the path into another tool call, looks for `src/main.rs`. It passed before only while some other hit line happened to contain `src/main.rs` inside the `search_max` cap.

Plan: `display_rel` in `src/plugins/read/search.rs` turns `\` into `/` on Windows only (`\` is a legal file-name byte on Unix); the existing case-insensitive test already accepts either spelling.

Check: the test passes in the `windows` CI job; `just check` stays green.

Do (Claude Code / claude-opus-5-5, 2026-09-24): `display_rel` turns `\` into `/` under `cfg!(windows)`. Merged as PR #354.

Check result: PR #354's `windows` job passed, `search_fn_main_finds_src_main` included; ubuntu and macOS green.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
