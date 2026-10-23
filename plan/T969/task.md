---
status: todo
priority: P2
complexity: 2
readiness: 0%
agent:
---

# T969. One slow-hook warning per event per interval, not one per call

`rtok.log` holds 27,107 `hook/PostToolUse … over max_ms 10 ms` warnings (some over 400 ms), one
per call. The flood hides every other warning in the log. The cause is T920. `max_ms` stays at
10 ms (the hook budget rule).

Done when: `note_slow` (`src/hooks/mod.rs`) writes at most one warning per event per interval
(configurable, a few minutes by default). That warning gives the count and the worst time since
the last one. The `calls` rows that `rtok stats` reads stay one per call.

Check: a unit test for the interval and the summary line; `just check`.
