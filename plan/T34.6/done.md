**T34.6 proxy: bounded tee, one usage reader** · P5 · `src/proxy/{mod,wire,anthropic,openai_chat,openai_responses}.rs`
Do: the response tee buffered the whole upstream body with no cap; each wire re-implemented usage extraction; "no usage in upstream response" was logged for every non-model path.
Check: the tee keeps at most `MAX_BODY_BYTES` (the client still gets every byte; the true size is logged when cut); `wire::find_usage` with per-wire field names; the log fires only for a known wire. Existing wire usage tests unchanged and green.
Complexity: 2/5
Status: done 2026-09-10 · Model: Sonnet 5 (subagent)
