**T34.7 `otel.headers` never prints** · P16 · `src/config/layers.rs`, `docs/config.md`
Do: `config show/get/set` and `rtok report` printed OTLP ingestion keys from `otel.headers`.
Check: `SECRET_KEYS` in `layers::entries` → `<redacted>` when set, source kept. Test `otel_headers_are_redacted_when_set`.
Complexity: 1/5
Status: done 2026-09-10 · Model: Opus 5
