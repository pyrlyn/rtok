**T4.2 `read` tool: full/lines** · T4.1 · `src/plugins/read/mod.rs`
Do: `read(path, mode=full|lines, range?)` with line numbers, size cap (default 20 K chars, then head/tail + archive id). Root guard: path must be under cwd or config `allow_paths`.
Check: read a 3-line fixture → 3 numbered lines; a 100 KB file → capped output + archive id; `../etc/passwd` → error.
Status: done 2026-09-02 · Check: `three_lines_are_numbered`; `hundred_kb_is_capped_with_archive_id`; `dotdot_etc_passwd_is_err`. `make check` green. Deviation: also `src/mcp.rs` (dispatch).
