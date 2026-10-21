**T5.2 `rtok proxy` lifecycle** · T5.1 · `src/proxy/cli.rs`
Do: `rtok proxy [--port] [--upstream] [--mode passthrough|compress]`; `rtok setup claude --proxy` sets `env.ANTHROPIC_BASE_URL` in settings.json (backup) and prints how to revert. `/health` endpoint.
Check: `curl :8790/health` → `{"ok":true,"mode":"passthrough"}`.
Status: done 2026-09-02 · Check: `proxy_health_reports_ok_and_mode` → `{"ok":true,"mode":"passthrough"}`; `proxy_env_dry_run_then_apply_is_idempotent` writes `env.ANTHROPIC_BASE_URL` to :8790 with revert text. `make check` green. Deviation: also `src/proxy/mod.rs` (`/health` route, `ProxyState.mode`), `src/cli.rs` (`--upstream`/`--mode`/`setup --proxy`), `src/config/layers.rs` (`proxy_flags`), `tests/proxy.rs`, `tests/config_coverage.rs` (`proxy --mode` → `proxy.mode`).
