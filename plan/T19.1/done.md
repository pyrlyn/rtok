**T19.1 `[dashboard]` config, CLI, flags** · files: `config/default.toml`, `src/config/mod.rs`, `src/cli.rs`
Do: `host = "127.0.0.1"`, `port = 3333`; `rtok dashboard --host --port`; figment `flag` layer.
**Check:** `rtok dashboard --help` lists `--host`/`--port`; `rtok config get dashboard.port` is 3333.
Status: done 2026-09-08
Model: Cursor Grok 4.6
Check result: `rtok dashboard --help` lists `--host` / `--port`; `rtok config get dashboard.port` → `3333`; `just check` green. Extra files beyond the task list (`layers.rs`, `validate.rs`, `src/dashboard/`, `crates/rtok-webui`, `src/plugin.rs` `DashboardPage`) are required for a compiling `Dashboard` subcommand and the later T19.2/T19.3 Checks.
