Re-checked the three open host questions in `research.md` against primary sources (2026-10-08). Skills (§10.1, §10.6): Cursor documents `disable-model-invocation`; OpenCode, Copilot, Gemini and the Agent Skills spec do not; Codex uses `agents/openai.yaml` `allow_implicit_invocation: false`. Subagent-start hooks (§23): Grok and Antigravity still document no output schema and no subagent-start context hook, so the verdicts stay. Hook ancestry (§26): Devin and Command Code document no parent process, still unverified. Cline (§26) has been a hook host since 2026-09-24 (`src/agents/cline`), so the ancestor rule applies to it, and its hooks docs name no parent process or session id (checked 2026-10-08, https://docs.cline.bot/customization/hooks), so it stays unverified. Windows: the no-ancestor limit is written in `docs/agents-and-worktrees.md`. No code changed; `doctor` cites no `disable-model-invocation` advice today, so the card's doctor premise was stale.

Check: `mise exec -- just docs-check`.
Result: exit 0, 11 suites ok. #842.
Status: done 2026-10-08

Model: Claude Code / claude-haiku-4-5
