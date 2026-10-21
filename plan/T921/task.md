---
status: todo
priority: P1
complexity: 3
readiness: 0%
agent:
---

# T921. Hook calls that hang until the host's 5 s timeout, and the Pre/PostToolUse/SessionEnd tail

Claude Code logged about 425 `hook_cancelled` events on rtok hook commands with
`timedOut: true, timeoutMs: 5000` in about 94 sessions (rtok project 250, other projects 175),
2026-09-14 to 2026-10-19: PreToolUse:Bash ~336, UserPromptSubmit 35, PreToolUse:Read 31,
SubagentStart 18. Bursts on 10-02, 10-08 (99), 10-11, 10-15/16. Each one stalls the tool call
5 s and skips rtok silently. `rtok-hook` bounds only the resident path (`TIMEOUT` 50 ms); when
no resident answers it runs `rtok hook` itself with no deadline, and the plugin `hooks.json`
falls back to `rtok hook` when `rtok-hook` is not on PATH. Host-measured p99 is 1.2–2.2 s for
PreToolUse/PostToolUse; SessionEnd has 39% of 1,354 runs over 10 ms (p95 93 ms, max 896 ms).
T687 covers only the p50, T525 only SubagentStart and PreCompact.

Done when: no hook call can outlive a deadline well under the host timeout on any path (the
fallback included), the cause of the hangs is named (store lock, disk, load, or the walk), and
SessionEnd meets `max_ms` at the median.

Check: a test where the fallback `rtok hook` is blocked (locked store, slow plugin) and the
caller still gets `{}` under the deadline; `tests/latency.rs` covers SessionEnd; `just check`.
