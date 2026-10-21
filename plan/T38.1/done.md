**T38.1 e2e for uncovered commands** · P1, 3/5 · `tests/commands_e2e.rs` (new)
Do: one case per uncovered command through the binary with an isolated HOME — `hook PreToolUse`/`SessionStart` exit 0 with a JSON object, `run echo` prints its output, a 50-line `run` prints a `[rtok <id> …]` trailer and `expand <id>` round-trips it (`--lines` slices, unknown id fails), `plugins` lists the 11 catalogue ids, `config init/path/show/get/set/validate` round-trips, `bench --dry-run` lists 6×2×3, `doctor` reports the chain, `stats --json` parses.
Check: `cargo test --test commands_e2e` green; `just check` green.
Complexity: 3/5
Status: done 2026-09-14 · Model: OpenCode / Muse Spark 1.3
Evidence: `cargo test --test commands_e2e` — 9 passed / 0 failed; `just check` green — fmt-check PASS, clippy PASS (`-D warnings`, workspace + wasm-guest), workspace tests PASS, build-min PASS, jscpd 1.26% lines under threshold.
Deviation: none — one new file, 187 LOC, no source changes.
