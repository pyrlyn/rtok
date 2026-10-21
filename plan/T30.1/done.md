**T30.1 config flag (tags default)** · T30.0 · `config/default.toml`, `docs/config.md`, config schema
Do: add a config flag that enables the LSP backend; tags-only when off. Document it.
Check: default keeps tags-only; enabling the flag is visible in `config show --sources`; `just check` green.
Complexity: 2/5
Status: done 2026-09-12 · Model: Composer 2.5
