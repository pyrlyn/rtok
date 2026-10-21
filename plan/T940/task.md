---
status: todo
priority: P3
complexity: 2
readiness: 0%
agent:
---

# T940. Graph `references` writes hit "database is locked"; session-only projects auto-registered

53 `graph/references … failed: database is locked` warnings (2026-10-05..20), 46 of them for
`~/Library/Application Support/ClaudeBar/Probe`, an app probe with 615 sessions that ran only
SessionStart/SessionEnd and was auto-added as a project; `~/GitHub` is a project too.

Done when: a session that ran no tool does not register a project, the locked `references`
write backs off instead of warning each time, and the existing probe project rows are dropped.

Check: tests for the no-tool session and the back-off; `just check`.
