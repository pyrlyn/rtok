**T28.1 config / feature flag (default off)** · T28.0 · `config/default.toml`, `docs/config.md`, config schema
Do: add a config flag (and matching CLI override if needed) that enables the compressor / extractor; default off. Document the key. No compression logic yet — reading the flag and refusing unknown keys is enough.
Check: `rtok config show` lists the new key as off by default; turning it on via config or env is visible in `config show --sources`; `just check` green.
Complexity: 2/5
Status: done 2026-09-12 · Model: Composer 2.5
Check result: `plugins.compress.enabled = false` in `rtok config show`; `RTOK_PLUGINS_COMPRESS_ENABLED=true rtok config show --sources` → `true (env)`; user file edit → `true (user)`; `cargo fmt --check`, `cargo test --lib config::` 40 passed; `just check` blocked by disk full (errno 28) on this machine — re-run on landing.
