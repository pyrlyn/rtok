---
status: todo
priority: P3
complexity: 1
readiness: 0%
agent:
---

# T942. `rtok expand -` used as a stdin pass-through by agents

About 46 `unknown archive id: -` (also `x`, `/dev/stdin`) from 2026-10-02 to 10-18, 17 of them
after T354's hint: agents run `cmd | rtok expand - || cmd`.

Done when: `rtok expand -` either passes stdin through unchanged or refuses with a one-line hint
that names the right use; pick one with the creator.

Check: a CLI test for `rtok expand -`; `just check`.
