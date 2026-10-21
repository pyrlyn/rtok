**T3.5 `rtok expand <id>`** · T3.1 · `src/expand.rs`
Do: print archived payload; `--lines a-b`; `--grep re`. Also exposed later as MCP tool (T4.1).
Check: `rtok expand <id from T3.1>` prints the raw output; unknown id → exit 1 with message.
Status: done 2026-09-02 · Check: unknown id exit 1 `unknown archive id`; round-trip `get_archive` matches bytes. `--lines`/`--grep` are action flags (T12.4 allow-list). Deviation: also `Store::get_archive`, `cli.rs`, `lib.rs`.
