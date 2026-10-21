**T29.1 config flag (FTS5 default)** · T29.0 · `config/default.toml`, `docs/config.md`, config schema
Do: add a config flag that selects the embed path; FTS5 remains default when the flag is off/absent. Document it.
Check: default config keeps FTS5-only behaviour; enabling the flag is visible in `config show --sources`; `just check` green.
Complexity: 2/5
Status: done 2026-09-12 · Model: Composer 2.5
Check: `default_toml_is_the_defaults` + `memory_embed_*` tests; `RTOK_PLUGINS_MEMORY_EMBED_ENABLED=true rtok config show --sources` → `plugins.memory.embed.enabled = true (env)`; `cargo fmt --check`; `cargo clippy --lib -D warnings`; `cargo test --lib config::` (41 passed).
