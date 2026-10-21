From run 35576438155. Windows os error 4390 is `ERROR_NOT_A_REPARSE_POINT` — the uninstall path expects a symlink/junction (reparse point) and finds a plain materialized copy instead, matching the card's "not a reparse point" note. Read `agent_remove`'s uninstall and decide whether the installer needs to detect a non-symlinked (materialized/copied) plugin directory on Windows and remove it by content instead of by reparse-point semantics, or the test fixture needs to actually create a reparse point. One family split out of the original T83; see T83.2 for the closing criterion.

Do (Claude Code / claude-opus-5-5, 2026-09-25): not the uninstall — the test's premise is Unix-only. It models a host that materializes our plugin symlink into a plain copy with the same bytes; on Windows `PluginLink` never makes a link — it installs a marked copy that Cursor's `windows_copy` `CopyFix` rewrites — so `fs::read_link(dest)` failed with 4390, and a first fix that deleted the marker instead failed the test's own sanity check (a rewritten copy is not the source's bytes; PR #370's `windows` job). There is no link on Windows for a host to materialize, so the test and its `copy_tree` helper are now `#[cfg(unix)]`, with the reason in the doc comment, and the line left the `cfg(windows)` filter in `.config/nextest.toml`.

Check: the test passes in the `windows` CI job; `just check` stays green.

Check result: `cargo nextest run --test agent_remove` green on macOS; `just check` green; PR merged only with green `check` and `windows` jobs.

Status: done 2026-09-25
Model: Claude Code / claude-opus-5-5
