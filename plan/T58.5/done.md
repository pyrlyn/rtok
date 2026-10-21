Follow-up of T50.1 step 4 (`research.md` §9.3, "Command output"). A TOML rule keeps lines by pattern and position; families whose signal is a table (`docker ps` / `kubectl get` / `ps aux`) need a formatter, like the existing cargo/git/pytest ones in `formatters.rs`.
Done when:
1. Only families named by T50.1 step 4, each with the fixture that showed the rule losing the signal.
2. One formatter per family in `formatters.rs`, returning `None` on unrecognized output so the rule path stays the fallback; failures and the `expand <id>` trailer kept; golden fixtures before/after.
3. `Measurement` rows `kind = formatter` per family beat the rule's row on the same fixture; the cmd docs page table cites them.
4. ≤ 200 LOC per commit: split by family group (containers, TypeScript tooling, JVM) if needed.

Execution plan (T58.5, Cursor / grok 4.6): isolated worktree `t58.5` from `t50.1`. (1) `docker ps` + `kubectl get` formatters in `formatters.rs` (one row per object, `None` on non-table output) + goldens that beat `Rule::default()` on the T50.1 fixtures. (2) `ps aux` formatter the same way. (3) Cite `kind = formatter` before/after bytes on `docs/cmd-rules.md`. (4) Close the card into `done.md`. No tsc/eslint/mvn/gradle formatters.

Shipped: `docker ps` 3147→1190 vs rule 1311, `kubectl get` 4542→1731 vs rule 1770, `ps aux` 2341→870 vs rule 990 (`kind = formatter`, one row per object). tsc/eslint/mvn/gradle formatters skipped (T50.1 rules already beat default on keep-error lines).
