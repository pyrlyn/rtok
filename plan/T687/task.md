---
status: todo
priority: P2
complexity: 4
readiness: 0%
agent:
---

# T687. Hooks: PreToolUse/PostToolUse p50 to 10 ms

Creator-approved (moved from `roadmap.md`, from T178). Cut another ~10 ms off the §19.2 harness p50 so PreToolUse and PostToolUse reach 10 ms as Claude Code sees them (today `research.md` §19.7: 12.3 / 12.5 ms on `rtok-hook`).

**Plan.** (1) Re-measure first with the §19.2 harness on an idle machine (load average low; if it is high, say so and record no number). (2) Profile the hook path (process start, config parse, store open, plugin dispatch) and remove the largest avoidable cost with a fail-first latency test. (3) Record a dated `research.md` row. The fail-open and byte-stable rules stay.

**Status (2026-10-16).** Not started on code: no idle machine to measure on. The rerun script and the reasoning for the first step are `research.md` §46. Needs a quiet machine (load average near zero), not a creator decision.

Check: p50 as Claude Code sees it is at or under 10 ms for both events on the §19.2 harness, or the measured floor and the reason are written up; `just check`.
