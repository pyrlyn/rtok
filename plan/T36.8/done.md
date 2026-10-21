**T36.8 legacy-key fold cannot outrank env or flags** · — · `src/config/mod.rs`, `src/config/layers.rs`
Do: `[dashboard]`, `core.log_file`, `core.log_level`, `core.log_to_db` and `core.inject_budget_tokens` are folded after `extract()`, so a stale file key overrides `RTOK_*` and `--flags` (`rtok web --port 5555` binds the file's 4444), and `config show --sources` reports the pre-fold value and source. Fold inside the figment below project/env/flag, or apply a legacy value only while the new key is still at its default, and build `--sources` rows from the folded result.
Check: a legacy file key loses to `RTOK_*` and to a flag; `show --sources` names the layer whose value is in effect.
Complexity: 4/5
Status: done 2026-09-11 · Model: Composer 2.5
Evidence: `mise exec -- cargo test config::` — 39 passed, including `legacy_dashboard_port_loses_to_higher_layers`, `legacy_dashboard_port_folds_when_unset`, `legacy_core_log_level_loses_to_env`; T36.7 `user_path`, T36.9 `finish()` tilde expansion, T36.10 cap field docs retained.
