**T31.1 config flag (opt-in, default off)** · T31.0 · `config/default.toml`, `docs/config.md`, config schema (`src/config/mod.rs`)
Do: add the opt-in flag and threshold knobs; default off. Document them. NO cache implementation, no embeddings, no new deps. Proxy behaviour must stay unchanged.
Check: default proxy behaviour unchanged; enabling the flag is visible in `config show --sources`; `just check` green.
Complexity: 2/5
Status: done 2026-09-12 · Check: `cargo fmt --check`, `cargo clippy --lib -D warnings`, `cargo test --lib config::` (40 passed incl. `semantic_cache_defaults_overlay_and_unknown_key`, `default_toml_is_the_defaults`), `cargo test config_coverage` green; no `src/proxy/` diff; `RTOK_PLUGINS_PROXY_SEMANTIC_CACHE_ENABLED=true rtok config show --sources` → `plugins.proxy.semantic_cache.enabled = true (env)`.
Model: Composer 2.5
