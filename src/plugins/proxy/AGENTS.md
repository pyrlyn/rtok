# Agent notes — `proxy`

**Owns** `src/plugins/proxy/**`, `src/proxy/**` (`mod.rs`, `cli.rs`).

**Contract**: the `Plugin` trait, `Ctx` and the host capabilities come from the published
`rtok-plugin-sdk` crate (`crates/rtok-plugin-sdk`), not from `crate::plugin` — import them as
`rtok_plugin_sdk::…`.

**Invariants**
- Passthrough is byte-exact: the T5.1 test compares response bytes against a mock upstream.
- Added latency < 20 ms per request (definition of done #4). Do not buffer SSE streams.
- Never modify `system` or the last 2 turns. `tools[]` stays byte-identical unless `[proxy.tools_rewrite]` is on (T59.5): then descriptions truncate at a sentence boundary and allow/deny drop names; a dropped tool's later call is still forwarded. `input_schema` / `parameters` is untouched unless `compact_schema` is on (T686, creator decision 2026-10-16): then only the `description` (first sentence), `default` (the type's zero value) and `examples` (repeating `default` or `enum`) strings inside it shrink, found by schema keyword so a property named `description` is safe; tool names, property names, `type`, `required` and `enum` values stay byte-identical, and enums are never compacted. Any change archives the original `tools[]` and records the id in `Measurement.ref_id` (`expand <id>`).
- Every request inserts one `usage` row with all four counters; session id comes from
  `metadata.user_id` or a header, else a request hash.
- Bind `127.0.0.1` only.

**Dependencies allowed**: `tokio`, `axum`/`hyper`, `reqwest` (streaming). One-line reason each.

**Checks**: `plan.md` T5.1, T5.2.

**Batch / Flex / routing** (see `docs/batch-flex.md`)
- Batch ≠ Flex ≠ model routing. Never auto-convert sync agent turns into Batch jobs.
- Batch paths (`/v1/batches`, `/v1/messages/batches`, …) miss every `Wire::matches` (exact
  sync paths only) and ride the axum fallback byte-for-byte. The lane tag records them as
  `api_request:batch`. `[proxy.batch] parse_results` (default off) writes `usage` from a
  forwarded results file (`src/proxy/batch_results.rs`) and does not change the response.
- Flex is OpenAI `service_tier` on sync Chat/Responses wires. `src/proxy/flex.rs` sets it in
  `prepare` for a lane with `flex` (`[proxy.flex]` chooses force and the 429 policy). It is
  not a separate HTTP API, and the `agent` lane never sets it.
- Model routing (D9, T385.10) swaps `model` for `internal` and `bulk` calls only
  (`src/proxy/route.rs`, `Lane::route`), opt-in per lane rule with a named quality `gate` (I-101);
  the `agent` lane is never routed. Sticky upstream (I-84) is still **planned**. Hooks/MCP never
  see this traffic.
- Extension points: Batch observe is the lane classifier plus `src/proxy/batch_results.rs`;
  Flex is `src/proxy/flex.rs`; routing rules are `src/proxy/route.rs`, applied first in
  `shape_request`. Do not teach hooks or MCP about LLM HTTP.

