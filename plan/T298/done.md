`tests/replay_bench.rs` put a floor under `cmd` and `read` only. The proxy methods (`archive`, `toon`, `compress`) had unit checks that output shrinks but no floor over a realistic request.

Result: `tests/proxy_bench.rs` sends `tests/fixtures/proxy/messages.json` (10-turn Messages request: a 200-line CI log, a 150-line `ls -la` listing and a postmortem as old tool results, two JSON arrays, one short body, four live turns) through the real proxy against an `httpmock` upstream. The floor is end to end, per `tool_use_id`: original content vs what upstream received, so an `archive` → `compress` chain on one block counts once. Measured 91.4 % (10,054 → 867 est. tokens), floor 86.4; per plugin (information only): `archive` 90.4 %, `toon` 50.9 %, `compress` 26.6 %. It also checks each plugin shrank at least one block, the short body arrives byte-identical, every archived id expands to the original bytes, terminal rows' `after_bytes` match the sent bytes, and that `archive` off drops below the floor. `Server`/`proxy_server` moved from `tests/proxy.rs` into `tests/common/proxy.rs` for both binaries. Noted: an `archive` row's `after_bytes` is the intermediate pointer when `compress` shrinks that block further; saved tokens (`before − after`) still add up across the chain, only a ratio of summed `before`/`after` would be skewed.

Check: `cargo nextest run -p rtok --test proxy_bench --test proxy` 34/34; clippy on both targets clean.

Status: done 2026-09-27
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
