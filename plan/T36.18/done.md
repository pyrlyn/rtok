**T36.18 one path→provider/api mapping and a real URL join** · — · `src/proxy/wire.rs`, `src/proxy/mod.rs`
Do: `Wire::api()` re-derives the name from `matches()` while each wire already knows its own provider, the `provider` fallback arm for `/v1/chat/completions` is unreachable, and the upstream URL is built by `format!("{base}{path}")` plus a hand-appended `?`. Give each wire constants for provider/api and join the URL with `Url`, so a base with a path or query cannot produce `//` or a doubled `?`.
Check: every wire reports its own provider and api; a base URL with a trailing path still forwards to the right target (test with a mock).
Complexity: 3/5
Status: done 2026-09-11 · Model: Composer 2.5
Evidence: `mise exec -- cargo test --lib wire_reports` — 1 passed (`wire_reports_own_provider_and_api`); `mise exec -- cargo test --lib join_upstream` — 1 passed (`join_upstream_avoids_doubled_slashes_and_queries`); `mise exec -- cargo test --lib upstream_base_with_trailing` — 1 passed (`upstream_base_with_trailing_path_forwards_to_target`); `mise exec -- cargo test --test proxy` — 21 passed; T36.11 `run_retention` at serve start retained.
