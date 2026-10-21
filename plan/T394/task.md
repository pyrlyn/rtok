---
status: todo
priority: P2
complexity: 2
readiness: 30%
agent:
---

# T394. Run the paid live benches and record them

From `research.md` §3 (coaching nudges A/B) and §2 (A/B bench T9.2, `graph` T68.9): every live arm is unrun ("No live token, cache, or USD rows … the gate is do not enable"; T68.9 "Live API clause remains open"). Gate P9 and P8b clause 4 stay open until they run. Needs the creator's session and API spend: an agent sandbox cannot run them (OAuth expired).

Done means: one dated row per suite in §2 (nudges off/on, T9.2 legacy vs rtok, T68.9 graph MCP vs native) with the command, `claude --version`, mean input/cache/output tokens, `stats --price` cost and pass rate. Nudges become default-on only if cost per passed task falls and the pass rate holds; otherwise the row says "stays off". Gate P9 and P8b clause 4 close or fail on these rows.

Check: the rows are in §2 and `docs/comparison.md`; the gates are marked.

Progress (2026-10-21, `research.md` §2 "Paid live benches: status and re-run protocol"): there are still no live rows. The agent session's permission policy refused to run `claude` (even `--version`), so nothing was spent. The harness dry-runs pass on main: 36 runs for T9.2 and 24 for T68.9. Reading `src/bench.rs` showed five problems that would make a paid row unreadable:

- a failed run counts as a 6/6 pass at $0, because every `bench/tasks.toml` check is `true`;
- the user's own settings and MCP servers load into every arm;
- the arms depend on proxies on `:8788`, `:8787` and `:8790`;
- the A/B tasks edit the caller's cwd;
- the graph cost column keys on a top-level `model` field.

The re-run protocol is written there. Left: the creator's paid run outside an agent sandbox. Because of the first problem, the Gate P9 "pass rate holds" clause needs real `check`s first.
