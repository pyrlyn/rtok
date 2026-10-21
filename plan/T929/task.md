---
status: todo
priority: P2
complexity: 3
readiness: 0%
agent:
---

# T929. Dedup stubs whose original is no longer in the agent's context (compaction, subagents)

After a Codex `compacted` event the agent got `identical to a result N turns ago` stubs for
results it no longer had, and re-read with `lines` mode or `expand`. 36
`unchanged since <sha8> (N lines)` MCP stubs went to agents that never saw the file, 30 of them
subagents (2026-09-14..10-16). T127 kept session-only scoping on purpose (`research.md` §17.3);
T122 and T531 are done.

Done when: a compaction (PreCompact/PostCompact or the host's equivalent) resets the dedup and
re-read windows of that session, and a subagent's first read of a file is never a stub (needs
the creator's OK to revisit T127's scoping).

Check: hook tests: a stub before compaction, the full result after; a subagent's first read is
whole; `just check`.
