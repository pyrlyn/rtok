**T14.5 `proxy` design** · T14.0 · `src/plugins/proxy/PLAN.md`
Do: survey proxy/gateway designs: the two retired proxies plus at least one outside source (LiteLLM, bifrost, OpenRouter, or an SSE-passthrough implementation). Answer: exactly which request mutations break prompt caching on each wire, and what a cache miss costs relative to the bytes any rewrite saves — this is the rule every other plugin's `proxy_filter` obeys. Decide the failure policy: what the proxy does when upstream is slow, streaming, or errors mid-stream.
Check: shared Check; `Target:` is the added-latency budget (< 20 ms) plus a cache-hit-rate floor.
Status: done 2026-09-02 · `src/plugins/proxy/PLAN.md`. Passthrough first; do not mutate cached prefix. Target: < 20 ms + cache-hit floor (P5 passthrough).
