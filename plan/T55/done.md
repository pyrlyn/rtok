**T55.1–T55.6** · P1–P2 · `src/plugins/read/search.rs`, `src/plugins/cmd/{hook,run}.rs`, `src/plugins/graph/lsp.rs`, `src/agents/mod.rs`, `src/plugins/guard/mod.rs`, `src/config/mod.rs`, `config/default.toml`, `docs/config.md`, `plugins/cursor/scripts/mcp.cmd`, `src/testutil.rs`, `tests/trycmd/config-show.stdout`

From review 2026-09-17 (docs PR #48 reverted on main). Six Windows/agent bugs fixed with regression tests; new tests prefer `testutil::Vfs` / pure `Path` where they touch files (D29 / T56).

Do:
- T55.1: `display_rel` strips prefixes ASCII-case-insensitively on Windows (aligned with `under`).
- T55.2: `never_wrap` stem match is ASCII-case-insensitive (`Sudo.exe`, `RTOK.EXE`).
- T55.3: graph `file_uri` percent-encodes spaces/reserved octets; `path_from_file_uri` decodes.
- T55.4: PreToolUse wrap uses `wrap_quote` (PowerShell-safe on Windows); `shell_quote_bin` uses cmd `""` escapes; guard `strip_wrap` accepts both POSIX and PS forms.
- T55.5: `plugins.read.search_max_bytes` (default 1 MiB); search skips oversized files before `read_to_string`.
- T55.6: `plugins/cursor/scripts/mcp.cmd` invokes `call rtok mcp`.

Check: unit tests in search/hook/run/lsp/agents/testutil; `cargo nextest` + clippy `-D warnings` + fmt; config-show snapshot includes `search_max_bytes`.
Status: done 2026-09-17 · Model: Cursor / Grok
Evidence: see PR for branch `fix/t55-windows-bugs`.
Deviation: T55.7 left open; VFS migration continues as T56.x (`Vfs` helper landed here).
