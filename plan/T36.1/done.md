**T36.1 toon escapes control characters in a quoted cell** · — · `src/plugins/toon/mod.rs`
Do: `encode_cell` detects `\n` and then emits it literally, so a row physically spans two lines while the header claims N; escape `\n`, `\r`, `\t` and unescape them in `decode_cell`.
Check: a fixture with a newline inside a cell round-trips (`round_trip_values`), and the emitted block has exactly N row lines. `cargo test --lib toon`, 7/7 pass, including `round_trip_values` rstest cases for `\n`, `\r`, `\t`.
Complexity: 2/5
Status: done 2026-09-11 · Model: Composer 2.5
