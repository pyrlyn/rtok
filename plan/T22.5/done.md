**T22.5 recommendations** · T22.1 · `src/report/advice.rs`
Do: rules over the ledgers, never a model call. Each finding prints what triggered it and the rows
it read. The first set, all answerable from data rtok already stores: expand rate above
`[expand] max_rate` (the compression is lossier in practice than it looks); a plugin whose net
saving over the window is ≤ 0 (it costs more than it returns — D10 says retire, not stack); cache
busts with cause `tools` or `system` (the host rewrites its tool list mid-session, and the turn is
named); hooks that fire often and produce no `Measurement` (weight on the 10 ms path for nothing);
measured injection bytes per turn against `[plugins.inject] budget_tokens`; `archive keep_turns`
against how often old tool results were actually re-read. Findings are ordered by the tokens they
would recover, and a finding with no number attached does not ship.
Check: a fixture store triggers each rule exactly once and the text names the row count behind it;
a healthy store produces an empty section that says so, not filler advice.
Complexity: 3/5
Status: done 2026-09-10 · Model: GLM-5.3 (subagent; tier GLM-5.3, effort High) — adopting the disconnected subagent-report-reco heap
Check result: green. Six rules in `src/report/advice.rs` over the D23 ledgers only (D24: no new
Store query): expand rate vs `[expand] max_rate` (key added, default 0.05); plugin net saving ≤ 0
(D10 retire); cache busts cause `tools`/`system` naming the turn; hooks firing ≥ 10 times with no
Measurement kind on their path; inject `est_after` per turn vs `[plugins.inject] budget_tokens`;
`archive keep_turns` vs observed re-reads. Findings ordered by tokens recoverable; a finding with
no number cannot be pushed. The model grows only what the rules read (`savings.kinds`,
`calls.hooks`, `cache.detail`, `expand.cost`/`cost_rows`). Empty section prints
`No recommendations.` Verified in worktree `rtok-wt-e`: `mise exec -- cargo test --test report`
6 passed (`each_rule_fires_once_in_recoverable_order`, `healthy_store_has_no_recommendations`,
plus the prior four).
Deviations: 7 files, +552/−15 — over the ≤200 / ≤3 brief; the Check needs the fixture seed, the
six rules, and the ledger fields the rules read. Precedent T22.1 (+880), T22.4 (+750). No new
dependency.
