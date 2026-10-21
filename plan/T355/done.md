Found 2026-10-02 in Claude Code transcripts (578 sessions, last 7 days): the read hook denied native `Read` 710 times ("use rtok read; before Edit run native Read(limit=N) — it satisfies the edit gate", T127) and the guard denied 59 duplicate reads. Each deny is an extra model turn plus a retry; there is no `Measurement` row showing the deny saves more tokens than it costs.

Done when: a measurement over the stored calls pairs each deny with the follow-up call (rtok `read`, ranged native `Read`, or giving up) and reports net tokens per deny; the result and a recommendation (keep, narrow, or turn into advice without deny) are recorded in this card. No behaviour change in this task.

Check: the measurement query/script is reproducible from the store; numbers recorded here.

Execution plan: read-only `sqlite3` queries over a copy of `~/.rtok/rtok.db` (hook `calls` + inline `call_io` bodies, last 7 days, while T352 still keeps them): find each `PreToolUse` `Read` the read hook denied, then the same session's next tool call — rtok `read` (MCP), ranged native `Read`, another tool, or nothing; estimate tokens of the deny turn (deny text + retried call) against the saving of the rtok `read` that replaced it (`measurements`). Queries go into `research.md` with the date; numbers and a recommendation (keep, narrow, advice-only) go here. No code change.

**Result (2026-10-02).** `research.md` §29 (query reproducible on a copy of the store). 984 denies in 77 sessions (2026-09-21…10-02). 687 (70 %) of the denied calls were already ranged (mean 122 lines ≈ 1,195 tokens); the 258 of them that went on to rtok `read` got 1,929 tokens back — net ≈ −780 tokens and one or two extra turns per deny (541 denies, 55 %, also paid a `ToolSearch` turn for the rtok schemas). Unranged denies that went on to rtok `read` (48) saved ≈ 18,000 tokens each. 516 denies (52 %) saw no read of the file within 5 minutes. Recommendation: narrow — let native `Read` with `limit` ≤ 300 pass, keep the deny for unranged reads and larger ranges, and write a `Measurement` per deny (separate task, not approved yet).

Status: done 2026-10-02
Model: Claude Code / claude-opus-5-5
