**T0.2 config + paths** · T0.1 · `src/config.rs`
Do: `~/.rtok/config.toml` (create with defaults if missing): `[core] db_path, archive_dir, estimator_chars_per_token = 3.5, inject_budget_tokens = 800`; `[plugins.<id>] enabled = bool` per catalogue id. Env override `RTOK_HOME`. Function `Config::load()`.
Check: `RTOK_HOME=$(mktemp -d) cargo run -q -- plugins` creates `config.toml`; a unit test asserts default budget 800.
Status: done 2026-09-01 · config.toml created with all 10 plugin ids; `config::tests::creates_defaults_and_budget_is_800` passes.
Deviation: estimator rates live in an `[estimator] code/prose/json/cjk` table (one key per class, as T0.5 needs) instead of a single `core.estimator_chars_per_token`. Per-plugin settings are free-form keys under `[plugins.<id>]` next to `enabled`.
