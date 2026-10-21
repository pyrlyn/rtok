Found 2026-09-23 in the D27 audit: `config show` and `config get` are exempt (`tests/surface_parity.rs:401-408`) although `model::config_entries` (`src/web/model.rs:1055`) already lists every key with its value and D12 source.

Plan: page `("config", "config")` — key, effective value, source (default / user file / project file / env / flag); read-only on both surfaces (writes stay CLI, D27); TUI tab with a `/` filter, Slint list with a filter box; both commands move to `COMMAND_PAGES`.

Check: `config_page_exists_on_both_surfaces`; a `tests/web.rs` case on a temp config with one env override shows the env source; `just check` green.

Result: New Config page ("config","config") on both surfaces: model::config_page_text renders config_entries rows as key = value (source) each tick; TUI tab with a / filter, Slint page with a filter box; read-only. config get gained --json {key,value,source}; config show/get moved from EXEMPT to COMMAND_PAGES/JSON_READERS. Tests: config_page_exists_on_both_surfaces, config_page_source_reflects_an_env_override (RTOK_PROXY_PORT → source env), webui snapshot parse.
