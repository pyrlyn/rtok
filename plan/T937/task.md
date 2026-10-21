---
status: todo
priority: P3
complexity: 2
readiness: 0%
agent:
---

# T937. `calls` never records failures, and MCP calls have no `ms`

Since 2026-10-01 `calls` has 0 rows with `ok = 0` or an `error`, although `logs` holds hundreds
of MCP errors; `ms` is NULL for all 8,223 MCP calls (`insert_call` in
`crates/rtok-store/src/call_log.rs` never sets them; only hooks and the proxy call
`set_call_ms`). Error rates and MCP latency cannot be read from the store.

Done when: MCP calls store `ms`, and failing calls on every surface store `ok = 0` and the
error text.

Check: store and MCP tests for a failing and a slow call; `just check`.
