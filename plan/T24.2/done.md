**T24.2 `rtok logs` and `rtok logs export`** · T24.0 · `src/cli.rs`, `src/log.rs`, `tests/logs.rs` (new), `src/render.rs`
Do: `rtok logs` prints the last `[log] lines` lines (`--lines N` overrides), newest first, reading
back through the rotated files as far as it needs; each line numbered, `1` being the newest, with
the level coloured through `render.rs` — one helper, not a second colour table. `rtok logs export`
is the same selection with no numbers and no colour, for `rtok logs export > my.log`.
Check: with 3 rotated files and `--lines 10`, the first line printed is the newest written and the
tenth is ten lines back across the file boundary; `export` output is byte-identical to those lines
with the numbering and ANSI stripped; both say so when nothing has been logged.
Status: done 2026-09-09 · Model: Opus 5 (subagent)
Check result: green — 16 `log::` unit tests, 3 `tests/logs.rs` integration tests against the real
binary, `config_coverage` unchanged (`--lines` was already in its ALLOW list). The integration test
seeds four lines in the live file and in each of three rotated siblings, then asserts line 1 is
`live-4` and line 10 is `r2-3`, which is two file boundaries back; `export`'s lines are the numbered
screen's with the `"<n> "` prefix removed; both commands print `no logs yet` on an empty store.
Deviations: two. (1) Four files: `render.rs` gained `log_line`, the one place a level is coloured.
The Do asks for the colour to go through `render.rs`, so the alternative was a second colour table
in `log.rs` — the duplication `just dup` now gates. (2) The subagent could not build in the shared
worktree while another session's SDK rename was mid-flight, so it verified in a detached
`git worktree` at HEAD holding only its four files, then removed it; the numbers above were re-run
in the main tree afterwards. That session's uncommitted `rtok_agent_sdk::backup` line in
`src/cli.rs` is excluded from this commit: it needs their `Cargo.toml`, which is not committed.
