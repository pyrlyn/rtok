**T36.7 `--config` is honoured by every `config` subcommand** · — · `src/cli.rs`, `src/config/validate.rs`, `src/config/mod.rs`
Do: `config init|set|path|validate` resolve `<home>/config.toml` and ignore `--config`/`RTOK_CONFIG`, while `show`/`get` honour it: `rtok --config ci.toml config set proxy.port 2222` reads ci.toml and writes the home file. Add one `Config::user_path(home, config_file)` and use it everywhere.
Check: `--config /tmp/ci.toml config set/get/validate/path` all act on `/tmp/ci.toml`; a test pins the path in the output.
Complexity: 3/5
Status: done 2026-09-11 · Model: Composer 2.5
Evidence: `mise exec -- cargo test config` — 32 passed, including `config_subcommands_honour_config_flag` (`path`/`get`/`set`/`validate` all honour `--config /tmp/ci.toml`).
