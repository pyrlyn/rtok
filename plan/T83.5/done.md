The desktop Claude config path assertion assumes a Unix absolute path or a Unix `rtok` binary name (no `.exe`). Read `src/agents/claude/mod.rs`'s desktop-config writer and decide whether it needs a `cfg(windows)` path/extension branch or the test's expected string needs a platform-aware fixture. One family split out of the original T83; see T83.2 for the closing criterion.

Result: not a product bug — the test matched `desktop_command()` as a substring of the raw JSON, and a Windows path's `\` is written as `\\` there. The test now compares the parsed `mcpServers.rtok.command`; `desktop_path()` / `desktop_command()` were already Windows-correct (`%APPDATA%\Claude`, absolute `current_exe`). Its line left the `cfg(windows)` `default-filter` in `.config/nextest.toml`.

Check: the test passes in the `windows` CI job; `just check` stays green.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
