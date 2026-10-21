The research sweep (2026-10-04) found statements that shipped work made false. Fix each in place with a date or a "shipped as Txx" pointer, following the §16.2 Status column:

- §2 T241 row caveats (see T397 for the numbers); §2 graph recall (fixed in T387, see `done.md`).
- §3–§9: §9.2–§9.4 describe T58.1, T58.2 and I-44–I-48 as open (all shipped as T58.x, T59.4–T59.8); the Cursor `afterMCPExecution` "unverified" claim is resolved; a blank line at the `rtok modes` row splits the P14 survey table; T134's cross-references point at the wrong lines; §5/§6 tool counts for `read` and `graph` contradict §9.3; §6 item 8 "adapter first" contradicts D6.
- §10, §13–§15 "today" cells refuted by T61.2/T62.x, T66.1, T69.1, T70.1–T70.3 and T304; the §13/§14 contradiction about checkpoint rows being "legacy unscoped" vs "under project `rtok`".
- §16–§19: T58.2 and T59.1 marked `open`; §16.5's "ship or schedule T59.5 and T61.2"; §16.3's ratings and "not yet a first-class idea" (I-84, I-85, I-86 rejected, I-101, I-102 exist); §17.1 "src/ has no agent_id" (T128, T129); §19.7 "T178 Check still not met" (raised to 20 ms, closed). `ideas.md` I-90 cites 17 % where §17 measures 14 %. I-99 and T156 gain the lead that `dunnage` 0.1.0 has its own `seed` and `worktree` subcommands (unmeasured).
- §22–§28: T283.3 shipped (line "Not shipped yet: (b)"); T330.1 no longer "PR #651, open"; host counts (22, not 17 or 21; plain host names, not autolinked URLs).
- `docs/config.md` `codex_dir` comment: only Cursor stores carry no token counts now (OpenCode and Copilot CLI are read by `rtok agents usage`).

Check: each listed statement is fixed or dated; the P14 table renders as one table; `just check` (docs tests).

Result: every listed statement in `research.md`, `ideas.md`, `plan.md` (T156) and `docs/config.md` (with the ru/uk copies) was checked against `done.md`, the code and `git log`, then dated or given a "shipped as Txx" pointer; the P14 table is one table; stale "12 hosts" cells in §9.3, §11 and §14 were dated too. Not done here: the §2 T241 `replay_bench` caveats, which T397 already owns (its re-run replaces them). Left for later: `README.md`, `docs/comparison.md` (en/ru/uk) and `roadmap.md` still quote the old 0.351 reference recall, and `src/plugins/read/README.md` says five MCP tools where it lists three. #818.

Status: done 2026-10-08
Model: Claude Code / claude-sonnet-5-5
