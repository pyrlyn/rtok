**T32.1 config / feature flag for WASM host** · T32.0 · `config/default.toml`, `docs/config.md`, `src/config/mod.rs`
Do: add the flag / Cargo feature that enables loading `.wasm` plugins; default off so in-tree builds stay unchanged.
Check: default build and config do not load WASM; enabling the flag is documented and visible; `just check` green.
Complexity: 2/5
Status: done 2026-09-12 · Model: Composer 2.5
Check result: `[plugins.wasm]` (`enabled = false`, `dir = "~/.rtok/plugins"`) in schema, `default.toml`, and `docs/config.md`; not in `CATALOGUE`; `RTOK_PLUGINS_WASM_ENABLED` in env leaf table; `mise exec -- cargo test --lib config::` — 43 passed; `cargo fmt --check` + `clippy -p rtok --lib -D warnings` green; `Cargo.lock` unchanged (no wasmi). `just check` lint step blocked here by missing `sccache` shim in cmake (pre-existing env).
