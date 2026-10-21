---
status: todo
priority: P1
complexity: 3
readiness: 0%
agent:
---

# T920. PostToolUse hook went from ~4 ms to ~50 ms with v0.17.0-alpha.2 (memory observations)

`calls` (surface `hook`, name `PostToolUse`): mean 4.0 ms on 44,982 runs from 2026-10-12 to
the alpha.2 install (2026-10-18 21:29), 53.5 ms on 3,385 runs since, minimum 45 ms. Every
PostToolUse run on 2026-10-19 is over `max_ms` 10 ms. The first `observations` row (T454,
merged 2026-10-09, first shipped in alpha.2) has the same second as the first slow call, and
there is about one row per PostToolUse since. `rtok-hook` prints `{}` after 50 ms
(`src/bin/rtok-hook.rs`), so many PostToolUse answers are now dropped, not only late. T687's
12.5 ms baseline predates this.

Done when: PostToolUse is back near its pre-alpha.2 latency with observations on (the insert
leaves the hook path or gets cheap), and a latency test covers PostToolUse with observations
enabled.

Check: `tests/latency.rs` case with observations on, red before the fix; p50 on the live store
for a day after install recorded in this card; `just check`.
