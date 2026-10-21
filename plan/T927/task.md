---
status: todo
priority: P2
complexity: 2
readiness: 0%
agent:
---

# T927. `memory/observe` measurement rows claim the whole tool output as saved

3,255 `memory/observe` rows since 2026-10-18 sum to about 1.9M estimated tokens saved (1.72M on
2026-10-19). Example: measurement row 126014, before 99,589 bytes, after 404 bytes. The agent
still gets the whole output; only the stored observation is short. The ledger sums every
(plugin, kind) (`crates/rtok-store/src/measure_rows.rs`) with no exclusion for it, against the
"no Measurement row = no saving claim" rule.

Done when: observations write no saving row (or a cost row like `memory/brief`), and existing
rows are dropped or excluded from the ledger.

Check: a store test that `memory/observe` adds nothing to the saved total; `rtok gain` on a
copy of the live store before and after, recorded here; `just check`.
