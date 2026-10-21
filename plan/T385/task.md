---
status: in progress
priority: P1
complexity: 5
readiness: 85%
agent: Claude Code / opus-5-5
---

# T385. Proxy lanes and the optimization plan from `docs/research/optimization.md`

From `research.md` §31 and `docs/research/optimization.md` §6 (2026-10-04): a 12-step plan, "proposal, nothing built", which says to promote items into `plan.md` before coding. `src/proxy/` has no lane code. This card is the spec; split it into sub-tasks (T385.1 …) when claiming, one PR each, in this order:

1. Lane classifier and a `calls.kind` tag (P1).
2. Per-lane policy: bulk and batch bodies stay byte-identical in `compress` mode.
3. Defaults bench for `compress` mode, context editing, skills and `live_blobs`, with a dated `research.md` row (`tools_rewrite` stays in T124).
4. Batch observe, with `parse_results` into `usage`.
5. Flex on bulk and internal lanes, with a 429 policy.
6. Per-lane cache-hit ledger and a replay byte-stability test.
7. Isolation: per-lane upstream and an in-flight cap.
8. P28 Phase 1 measurement.
9. P28 Phase 2 async compressor on the `internal` lane.
10. Routing for internal and bulk calls (I-101's quality gate applies).
11. Deferred tool schemas and thinking replay (I-85, I-86 evidence first).
12. `rtok batch` CLI and a lane breakdown in `rtok report`.

Also: a cross-session read-dedup measurement from `calls` (optimization.md §5, "measure first"). Related ideas: I-84, I-85, I-86, I-101, I-102, I-21. The optimization doc's own ids (T250–T259) collide with `done.md`; use T385.x.

Plan: split 2026-10-04 into T385.1–T385.13 below, one PR each, taken in id order (T385.3, T385.8 and T385.13 are measurements and may run in parallel with the build steps). Boundaries from `docs/batch-flex.md` hold throughout: never convert a live agent turn into a Batch job, never rewrite Batch JSONL, everything lives under `src/proxy/`.

Check: each sub-task carries its own Check; this card closes when every step is done or dropped with its number in `research.md`.

Progress (2026-10-21): every build step and measurement an agent can run is in `done.md` (T385.1, .2, .4–.8, .9.1, .9.2, .10–.13). Open: T385.3 (offline half recorded in `research.md` §16.10, live arms left) and T385.9 via T385.9.3 (live Gate P28 bench, harness `rtok bench --suite p28` built); both need the creator's API spend (T394).
