**T25.1 one reader, in the model** · T25.0 · `src/store/mod.rs`, `src/web/model.rs`
Do: `Store::session_totals(since)` — one `GROUP BY` over `sessions` joined to `usage` and `calls`,
returning id, host slug, provider/api, model, the four token counts, `started_at`, last activity
and `ended_at`. It lands in the D23 model as a `Sessions` page, which is what makes it a `rtok web`
and `rtok tui` page and not just a command (D27). No second query anywhere.
Check: a fixture DB with three sessions across two hosts totals each one's tokens exactly, and the
model's page carries the same numbers as the store call; `rtok web`'s snapshot gains the page.
Complexity: 3/5
Status: done 2026-09-09 · Model: GLM-5.3 (subagent, effort High)
Check result: green. One statement: `sessions` LEFT JOINed to four per-session CTE aggregates —
`tot` (token SUMs over `usage`), `last_u` (newest usage row's api/model), `act` (MAX(ts) over
usage UNION ALL calls — no `[agents] idle_secs`, per T25.0's deviation), `prov` (newest
provider-bearing call's slug). CTE pre-aggregation is required: a flat sessions×usage×calls join
fans each usage row across each call row and multiplies the sums. `since` windows sessions, not
tokens. Contract for T25.2: `session_totals(&self, since: i64) -> Result<Vec<SessionTotals>>`,
rows newest first, `ended_at IS NULL` = live, zeroed session still appears with
`last_activity = started_at`; `SessionTotals` is documented field-by-field. `Snapshot` gains
`sessions` (existing keys untouched — the P19 shape test passes unmodified) and `pages()` gained
`("sessions", "sessions")` in the same commit, which keeps the T15.10 parity gate green. Fixture:
three sessions across `claude` and `pi`, one ended, one zeroed — store call, model page and
snapshot frame all asserted equal. `just check` exit 0.
Deviations: host slugs are `claude` (0002) and `pi` (0010), not `claude-code` as the briefing
guessed. `SessionTotals` carries `project` beyond the task's field list — P25's gate ("every
session has a host and a project") and T25.2's "sessions active in this project" need it. Diff
+397/−2 exceeds the ≤200-LOC line; ~265 of it is the two test groups the Check requires, product
code ~150 in the task's two files.
