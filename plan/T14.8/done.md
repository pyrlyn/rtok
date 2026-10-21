**T14.8 `memory` design** · T14.0 · `src/plugins/memory/PLAN.md`
Do: survey agent memory: the two retired memories plus at least one outside source (mem0, MemGPT/Letta, or a retrieval evaluation). Answer: does zero-LLM memory (the agent writes its own notes, FTS5 retrieves) recall as well as LLM extraction, and by what metric would we know? Decide what is stored, what is never stored, and how recall stays inside the `inject` budget.
Check: shared Check; `Target:` is a recall statement on a fixture note set plus the P6 injection-token comparison.
Status: done 2026-09-02 · `src/plugins/memory/PLAN.md`. Zero-LLM FTS5; titles first. Target: fixture recall + P6 injection comparison.
