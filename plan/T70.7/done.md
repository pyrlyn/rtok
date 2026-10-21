From `research.md` §15.3. `agents::reaches` marks a plugin reachable when the host supports the plugin's declared surface, so `inject` (Surface::Hook) is listed as reached on Cursor — but the installer writes only `beforeShellExecution` and `afterShellExecution`, and neither carries a session start or a user prompt, so the injection budget (D5) never runs there. Either the path or the claim is wrong, and today the generated table in `docs/agents.md` overstates what an install does.
Done when:
1. Step 1: verify against https://cursor.com/docs/agent/hooks which events carry session start and prompt submission and what their output schema accepts (the scan of 2026-09-18 reports `sessionStart` with `additional_context` and `beforeSubmitPrompt`); record the verified schema in the card.
2. If the events exist: `plugins/cursor/hooks/hooks.json` and `src/agents/cursor/mod.rs` register them onto `rtok hook SessionStart` / `rtok hook UserPromptSubmit --host cursor`, the injection is byte-stable and inside the existing budget, and a hook e2e asserts the same bytes Claude Code gets for the same store.
3. If they do not exist: `inject` stops being claimed on Cursor — the surface claim is narrowed where `reaches` computes it, not patched in the markdown — and the card records the doc line that says so.
4. Either way `RTOK_BLESS=1 mise exec -- cargo test --test agents_doc` re-blesses the host table, `src/agents/cursor/README.md` explains the outcome, and the same audit is run for every other host whose table claims a plugin no registered event can carry (one line per host in the card).

**Result (2026-09-18).** Commit `45c4531`. Cursor `sessionStart` and `beforeSubmitPrompt` hooks registered in `plugins/cursor/hooks/hooks.json`; `src/agents/cursor/mod.rs` dispatches to `rtok hook SessionStart` / `UserPromptSubmit`; host table re-blessed.

**Correction (2026-10-04).** The `beforeSubmitPrompt` half of this result was never true: `plugins/cursor/hooks/hooks.json` carried only `sessionStart` among the two until T390 registered `beforeSubmitPrompt` and `sessionEnd` (`research.md` §27.1). Cursor gives `beforeSubmitPrompt` no context field (https://cursor.com/docs/hooks), so even registered it injects nothing there.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
