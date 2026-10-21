**T24.5 retire unread `[core] log_file` / `log_level` / `log_to_db`** · T24.1 · `src/config/`, `config/default.toml`, `docs/config.md`
Do: the three `[core]` keys stay in the schema and the reference file but production readers use only `[log]` (D26). Fold them the way `[dashboard]` folded into `[web]` (accept once with a warning, map into `[log]`), then drop them from the typed schema so `[log]` is the only authority.
Check: a config that sets only legacy `core.log_*` loads into effective `[log]` with a warning; after the drop, `rtok config validate` rejects the old keys; `docs/config.md` and `config/default.toml` match the schema; `just check` green.
Status: done 2026-09-10 · Model: GLM-5.3
Check result: green. `core.log_file` / `log_level` / `log_to_db` are `Option` with `skip_serializing_if`, removed from `default.toml`; `finish()` migrates into `[log]` with one warning each and `take()`s them. `legacy_core_log_keys_migrate_into_log` and `validate_rejects_legacy_core_log_keys` green; `default_toml_is_the_defaults` green. Docs Legacy keys section + reference file match.

Tasks move here from `plan.md` when their Check passed, `make check` is green, and the work is
committed as `<task-id>: <title>`. Newest phase first. Task text is kept verbatim so the
history of what was asked stays readable next to what was delivered.
