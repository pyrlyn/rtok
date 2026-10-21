---
status: in progress
priority: P3
complexity: 5
readiness: 75%
agent: Claude Code / claude-opus-5-5
---

# T385.9. P28 Phase 2: async compressor on the `internal` lane

optimization.md §3.3. An LLMLingua-class compressor (arXiv 2403.12968; ACON arXiv 2510.00615 as the agent-context variant) runs on the `internal` lane, asynchronously, cached per archive id; the agent lane never waits for it; the original stays expandable. Only if T385.8 clears Gate P28.

Check: Gate P28 — the must-keep fixture survives, the bench beats v0.1 lossless on cost per passed task; `just check`.

Creator decision 2026-10-11: build it behind a flag that is off by default, tested against a mock upstream; the task closes only after the live Gate P28 bench (T385.9.3, needs the creator's API spend). Split (complexity 5): T385.9.1 client and cache, T385.9.2 asynchronous use in the live zone, T385.9.3 the live bench.
