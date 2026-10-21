Found 2026-09-24 while implementing T239 (PR #276): `emit_filtered` in `src/plugins/cmd/run.rs` printed `filtered`, a padding newline and — when `needs_pointer` holds, i.e. every body past `trailer_min_lines` (default 40) — the `[rtok <id> · N lines · expand …]` trailer, but recorded `after_bytes`/`est_after` from `filtered` alone. The row understated what the host received by the trailer's ~170 bytes.

Result: `emit_filtered` builds the exact printed string once (filtered + optional newline + optional trailer line), prints it, and measures it. T239's `plugins_e2e::cmd_hook_measurement_matches_returned_bytes` drops its `trailer_min_lines = 100000` workaround: under the default config it asserts the trailer is printed and `after_bytes`/`est_after` equal stdout byte-for-byte (without the fix: 426 recorded vs 600 printed).

Check: `cargo nextest --test plugins_e2e`; `just check`.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
