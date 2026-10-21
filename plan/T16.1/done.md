**T16.1 `[otel]` config** · — · `src/config/mod.rs`, `config/default.toml`, `docs/config.md`
Do: `section! { Otel { endpoint: String = "", headers: String = "", service_name: String = "rtok", content: bool = true, content_bytes: u32 = 65536, flush_secs: u32 = 5 } }` and `Config.otel`; `Otel::resolve() -> Option<Endpoint { url, headers: Vec<(String, String)> }>`: empty `endpoint` → `OTEL_EXPORTER_OTLP_ENDPOINT`, empty `headers` → `OTEL_EXPORTER_OTLP_HEADERS` (`k=v,k2=v2`), `None` when both are empty, trailing `/` stripped. Same keys in `config/default.toml` and `docs/config.md` (D12).
Check: `default_toml_is_the_defaults` and `config_coverage` green; unit tests: empty → `None`; env fallback; `a=1,b=2` → two pairs; `rtok config show` prints `[otel]`.
Status: done 2026-09-04 — `default_toml_is_the_defaults`, `config_coverage` and `otel_is_off_until_an_endpoint_resolves` green; `[otel]` in `config/default.toml` and `docs/config.md`; `just check` green.
Model: Claude Fable 5.1
