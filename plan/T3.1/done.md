**T3.1 `rtok run -- <cmd>`** · T0.3 · `src/plugins/cmd/run.rs`
Do: run via `$SHELL -lc`, capture stdout+stderr (merged, ordered), preserve exit code, write raw output to `~/.rtok/archive/<id>` and an `archive` row; print output (unfiltered in this task) plus trailer `[rtok <id> · N lines · expand: rtok expand <id>]` only when > 40 lines.
Check: `rtok run -- printf 'a\nb\n'` prints `a b`, exit 0, no trailer; `rtok run -- sh -c 'exit 3'` → exit 3.
Status: done 2026-09-02 · Check: `printf 'a\nb\n'` → stdout `a\nb\n` exit 0 no trailer; `sh -c 'exit 3'` → exit 3. Deviation: also `Store::put_archive`, `cmd/mod.rs`, `cli.rs`.
