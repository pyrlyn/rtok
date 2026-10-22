`src/otel/export.rs` has no unit tests; `tests/otel.rs` covers the happy path against a mock collector. Add units for `resource()` attributes, an unreachable collector (error returned, no row marked, no panic) and ticker shutdown. Skip cases `tests/otel.rs` already pins.

Check: `just test` green; no new dependency.

Do (2026-09-21): a `tests` module in `src/otel/export.rs`, six units, no new dependency. `resource()` pins the four attributes with a custom `service_name`. An unreachable collector (a loopback port bound then dropped, so the connect is refused at once): `flush_blocking` returns one error naming all three streams, posts nothing, skips nothing, leaves every mark (`calls`, `logs`, `sessions`, `sessions_tail`) at 0, writes one `otel` `error`/`flush` log row and does not panic. No endpoint: the report is `Report::default()` and prints `otel: no endpoint`. Ticker: the thread has no stop handle and dies with the process, so the units pin what callers rely on — `spawn_ticker` returns at once, does not flush before its first period, and spawns nothing without an endpoint. `push_error` joins stream errors into one report line. The 404, 500 and happy paths stay in `tests/otel.rs`.

Check result (2026-09-21): `cargo nextest run --lib otel::export` 6/6 green; `just check` green.
