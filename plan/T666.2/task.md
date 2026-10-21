---
status: todo
priority: P2
complexity: 3
readiness: 0%
agent:
---

# T666.2. Provider-backed memory extractor (blocked on the creator)

T666.1 ships `rtok memory extract` with a user-configured program as the extractor. A built-in extractor needs creator decisions first: which provider and model, how the key is stored and read, and a per-run cost cap. Once decided: a provider extractor behind the same `Extractor` trait, a docs warning about cost, a measured row in `research.md` (needs a paid key, so the harness skips without one). Not claimed.

Check: a fake-server test of the provider call; the cost cap stops a run; off by default; `just check`.
