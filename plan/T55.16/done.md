**T55.16 Guard deny loads the whole archive on the PreToolUse hot path** · P3, 1/5 · `src/plugins/guard/mod.rs`, `src/plugins/guard/AGENTS.md`, `src/store/mod.rs`, `src/plugin.rs`, `crates/rtok-plugin-sdk/src/{host,testing}.rs`

From review 2026-09-17, second pass (code read, then fixed). `guard::pre_tool` called `cx.get_archive` and estimated tokens over the full body just to fill the denial Measurement — megabytes across the ≤ 10 ms hook path, against the plugin's own "no filesystem reads" invariant.

Do: `Archive::archive_size` on the SDK contract (default `Ok(None)` = fail open, so in-memory hosts need no change), backed by `Store::archive_size` (row `bytes` + a file-existence stat on `dir/<id>` else the stored path — never a body read). The deny's `est_before` becomes the same `bytes/4` heuristic `record_context_path` and the semantic-cache measurement use.
Check: `deny_does_not_read_the_archive_body` (unix; a chmod-000 body file still denies — the old body read would have failed open), `missing_archive_fails_open` and the rest of the guard suite unchanged; `just check` green.
Status: done 2026-09-17 · Model: ZCode / GLM-5.3
Evidence: `just check` green — 845/845 tests, fmt, clippy `-D warnings`, build-min, jscpd.
Deviation: none.
