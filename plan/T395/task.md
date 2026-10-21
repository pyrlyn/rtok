---
status: todo
priority: P3
complexity: 2
readiness: 30%
agent:
---

# T395. One real session as one OpenTelemetry trace in SigNoz and Maple

From `research.md` §2 "OpenTelemetry export (Gate P16)": still open is one real Claude Code session (hooks, MCP and proxy) exported as a single trace with an `invoke_agent` root and `chat {model}` spans, checked in SigNoz and Maple. `roadmap.md` says these clauses are Gate P18, but P18 in `done.md` has no such clause, so the item is orphaned. Needs a SigNoz or Maple account.

Done means: the `docs/otel.md` recipe for each backend is run with the proxy in front of a live session; the trace shape and `rtok_tokens_total` are recorded in §2 with a date, or the clause is dropped explicitly with the reason.

Check: dated rows in §2 for both backends, or the dropped clause in `roadmap.md`.

Progress (2026-10-21, `research.md` §2 "T395 status"): not run. This agent session's permissions refused `docker` and `colima`, so self-hosted SigNoz could not start. There is no SigNoz Cloud or Maple key, and agents never run a live host session behind the proxy (T280). The offline shape (hook, MCP and proxy rows → `invoke_agent` root plus `chat {model}`) is already asserted by `tests/otel.rs` `every_row_posts_once_and_the_marks_advance`. The orphaned P18 pointer in `roadmap.md` P16 now names T395. A re-run protocol is in §2. Left: the creator runs it once per backend and records the rows, or drops the clause with a reason.
