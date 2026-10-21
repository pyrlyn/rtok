From `research.md` §10.4: descriptions over 120 characters cost about 1.3 K tokens per request for 66 skills. `doctor` flagged only `desc > 200` and `body > 8192` and printed no saving.

Done: the doctor skills section adds the line "descriptions over 120 chars: N skills, ≈ X tokens/request recoverable" (sum of characters above the cap / 4, the same estimate as the section header; omitted when nothing is over the cap). The per-row flag is now `WARN desc>120`. The cap is one constant, `SKILL_DESC_MAX` in `src/agents/skill.rs`, shared with the `tests/skill.rs` check on rtok's own skills. Advice only.

Check: `doctor::tests::skills_audit_prices_the_description_cap` (one long, one short, one at the cap); the `report-md.toml` golden; full nextest 2760 passed.
Result: PR #847.
Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
