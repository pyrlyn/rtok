**T13.4 config keys** · T12.1, T13.3 · `config/default.toml`, `docs/config.md`, `src/config/mod.rs`
Do: `[core] call_io_inline_bytes = 65536`, `retain_calls_days = 30` (0 = keep forever), `log_to_db = true`. Document: hook path never archives `call_io`; `log_file` is always written; `logs` table is written when `log_to_db`. `rtok stats` later joins `calls`/`tokens`; no new CLI in this task.
Check: `cargo test config::` parses the three keys; `docs/config.md` has a row for each; T12.4 coverage still green once those tasks exist, otherwise the keys are present in `default.toml`.
Status: done 2026-09-02 · `cargo test config::` and `cargo test config_coverage` green. Keys documented next to `session_env` in `docs/config.md` and `config/default.toml`.

Gate P13 (review) · Status: done 2026-09-03 · Check: no rusqlite; hook `oversized_hook_call_io_does_not_archive` leaves `call_io` archive columns null; archive `plugin_run` has before and after token phases. §6: `migrations/*.sql` are Store-included schema, not runtime SQL outside `src/store/`.
