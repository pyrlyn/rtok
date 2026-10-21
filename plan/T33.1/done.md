**T33.1 config flag (default off)** · T33.0 · `config/default.toml`, `docs/config.md`, config schema
Do: add the opt-in flag for tiered loading; default off (v0.1 archive+inject unchanged). Document license note beside the key.
Check: default behaviour unchanged; flag visible in `config show --sources`; `just check` green.
Complexity: 2/5
Status: done 2026-09-12 · Model: Composer 2.5
Check: `plugins.archive.tiers` default false; TOML/env overlay; `RTOK_PLUGINS_ARCHIVE_TIERS=true` → `(env)` in `config show --sources`; unknown archive keys denied; `cargo test --lib config::` 41 passed; clippy clean; fmt ok.
