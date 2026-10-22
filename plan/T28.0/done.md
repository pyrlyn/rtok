**T28.0 design note: LLM compression vs lossless** · — · `src/plugins/compress/PLAN.md` or `src/plugins/memory/PLAN.md` (extend), `docs/` as needed
Do: D15-style survey of LLMLingua-2, claude-mem extraction, and at least one other compressor. Name the mechanism rtok will use, what stays lossless, what is default-off, and the falsifier (cost per passed task rises, or expand cannot recover a non-regenerable original). No implementation.
Check: PLAN (`src/plugins/compress/PLAN.md`) names LLMLingua-2, claude-mem, Selective Context (and headroom); mechanism archive-first semantic shrink; Gate P28 = cost per passed task ≤ v0.1 lossless bench; falsifier cost-up or expand fails.
Complexity: 3/5
Status: done 2026-09-11 · Model: Composer 2.5
