From I-33. OTel export is gated by mock collectors; the Jaeger 2.11 and Grafana `otel-lgtm` recipes in `docs/otel.md` were checked by hand once.
Done when `just otel-check` starts both containers on shifted ports, flushes a copy of a fixture ledger, and asserts through their APIs: Jaeger has `execute_tool` spans for `service=rtok`, Tempo answers the trace id, Prometheus has `rtok_calls_total`; it skips with a clear message when Docker CLI / Colima is missing, and it stays out of `just check`.

**Result (2026-09-18).** Commit `45c4531`. `tools/otel-check.sh` and `just otel-check` recipe; `tests/otel.rs` smoke test; skips cleanly without Docker.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
