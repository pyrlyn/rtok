Cloud review finding (2026-10-08): `decode` in `src/plugins/toon/mod.rs` is called only from the module's tests, and carried `#[allow(dead_code)]`. It, `decode_cell` and `unescape_quoted_cell` (used only by `decode_cell`) now sit under `#[cfg(test)]`; the three allows are gone, so a release build no longer compiles them. `decode` stays as the round-trip oracle the plugin's AGENTS.md requires.

Callers: `decode` in the two round-trip tests of the same module; nothing in `tests/`, `benches/`, `examples/`, other crates or docs.

Check: `just fmt-check`, `just lint` (clippy `-D warnings` on the lib and its test build), `cargo nextest run -p rtok -E 'test(toon) | test(plan)'`.
