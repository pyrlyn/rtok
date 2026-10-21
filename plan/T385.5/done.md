optimization.md §2.2 L4 (roadmap S4). Inject `service_tier = "flex"` only on `bulk`/`internal` (never silently on `agent`), OpenAI only (Anthropic has no Flex tier). On `429 Resource Unavailable`: retry policy `none` / `backoff` / `default` (retry with `service_tier = "auto"`). Cite the OpenAI Flex docs with the date checked.

Check: mock upstream — omit/force/respect matrix and each 429 policy; `just check`.
Result: `flex` is a column of `LanePolicy` (`[proxy.lanes.<lane>] flex`, `agent` always false) and `[proxy.flex]` has `force`, `on_429` (`none`/`backoff`/`default`), `retries` and `backoff_ms`. `src/proxy/flex.rs` splices an omitted `service_tier` in after the opening brace on the OpenAI wires only, respects any client value unless `force`, and `flex::send` retries a Flex 429 that rtok itself set, within bounds (at most 5 retries, delay capped at 30 s; `Retry-After` is not read). OpenAI Flex guide (https://developers.openai.com/api/docs/guides/flex-processing) checked 2026-10-08; it names no 429 error code, so the status alone identifies the capacity miss. 7 mock-upstream tests plus unit tests; `just check`: 2735 passed, 8 skipped. #836.
Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
