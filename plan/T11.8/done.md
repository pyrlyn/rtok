**T11.8 `toon` rewrite respects archive live-zone `keep_turns`** · T11.7 · `src/plugins/toon/mod.rs`
Do: `toon`'s `proxy_filter` rewrites every tabular tool result it sees. Archive's live zone (`plugins.archive.keep_turns` turns from the end) must stay untouched — the same boundary `archive` already uses — so a just-returned table is not TOON-encoded while it is still live context.
Check: with `keep_turns = 2`, tabular JSON in the last two turns is unchanged and older tabular blocks encode; Measurement rows only for the older ones; default-off still leaves request bytes identical; `just check` green.
Complexity: 2/5
Status: done 2026-09-10 · Model: GLM-5.3
Check result: green. `toon` skips `result.turn < archive.keep_turns` (same live-zone rule as archive). Test `live_zone_turns_are_untouched`; related toon/lib tests green.
