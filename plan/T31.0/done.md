**T31.0 design/survey: semantic cache** · — · `src/plugins/proxy/PLAN.md` or `src/proxy/` design note
Do: survey bifrost and at least two other semantic-cache approaches. Define similarity threshold, opt-in shape, and how false hits are measured on the P9 task set. No implementation.
Check: PLAN (`src/plugins/proxy/PLAN.md`) names ≥ 3 alternatives (bifrost, GPTCache, RedisVL); rejected wrapping; false-hit protocol = 0 false-hit pairs on frozen P9 `call_io` at cosine ≥ 0.99.
Complexity: 3/5
Status: done 2026-09-11 · Model: Composer 2.5
