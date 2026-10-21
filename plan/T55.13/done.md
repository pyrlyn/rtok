**T55.13 Copilot `Read` events use `path`, guard and read-advice match `file_path` only** · P3, 1/5 · `src/plugins/guard/mod.rs`, `src/plugins/read/{mod,hook,cache}.rs`

From review 2026-09-17, second pass (reproduced with a scratch test, then fixed). `hooks::types::adapt_copilot` maps `view`/`read_file` to tool name `Read` but keeps the Copilot input key `path`, while `guard::cache_key` and `read::hook::pre_tool` read `file_path` only — on the Copilot host the re-read deny and the large-file advice never fired (`cache::invalidate` already accepted both keys).

Do: one `read::path_arg` helper (`file_path` **or** `path`) used by `read::hook::pre_tool` and `cache::invalidate`; `guard::cache_key` takes the same two keys inline (no cross-plugin feature dependency for a one-liner).
Check: `copilot_path_key_dedups_like_file_path` (guard deny fires for both keys, reason names `rtok expand`), `copilot_path_key_gets_the_read_advice` (100 KiB file denied with `rtok read` in the reason), existing `file_path` tests unchanged; `just check` green (fmt, clippy `-D warnings`, nextest 838 passed, build-min, jscpd).
Status: done 2026-09-17 · Model: ZCode / GLM-5.3
Evidence: `just check` green; both new tests pass on macOS.
Deviation: none.
