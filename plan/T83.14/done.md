From run 35576438155. The `SessionStart` graph-map payload came back empty on Windows where the test expects populated content — likely a path-walk or capped-map computation that silently no-ops on a Windows path shape. Read the `graph` plugin's `SessionStart` map builder and decide whether it has a real Windows path-handling bug or the test's fixture repo isn't discoverable under Windows path conventions. One family split out of the original T83; see T83.2 for the closing criterion.

Do (Claude Code / claude-opus-5-5, 2026-09-25): not the graph plugin — the test. It spliced `repo.display()` into a JSON literal with `format!`; on Windows the temp path's `\` makes invalid escapes (`\U…`), so the hook could not parse its stdin, failed open and printed `{}`. The hook input is now built with `json!`. Its line left the `cfg(windows)` filter in `.config/nextest.toml`.

Check: the test passes in the `windows` CI job; `just check` stays green.

Check result: `just check` green on macOS (1772 passed); PR merged only with a green `windows` job.

Status: done 2026-09-25
Model: Claude Code / claude-opus-5-5
