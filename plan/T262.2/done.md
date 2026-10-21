Creator request 2026-09-24: extend the `SubagentStart` spawn brief (T130.2) beyond Claude Code where a host supports it. Known so far: VS Code links `plugins/claude` and already gets it; Kimi fires `SubagentStart` but discards the hook's result (`sessionExternalHooksService.ts` in MoonshotAI/kimi-code awaits `runner.trigger` and ignores it), so a brief there saves nothing; CodeWhale's `subagent_spawn` is observe-only. Check the rest (Codex, Cursor, Copilot CLI, Gemini, Grok, ZCode, OpenCode, Pi) from docs or source, and record the result in `research.md`.

Plan: one doc/source pass per host (Sonnet sub-agent for the lookups, results re-checked against the cited source), then `research.md` §23 with a host table and follow-up tasks for the yes rows.

Check: a `research.md` section lists each host with a source link and a yes/no; follow-up tasks only for the yes rows.

Do (Claude Code / claude-opus-5-5, 2026-09-24): `research.md` §23. Yes: Codex (`SubagentStart`, stdout becomes developer context) and Copilot CLI (`subagentStart`, `additionalContext`), both re-read first-hand; follow-ups T262.3 and T262.4. Event-only: Kimi, Cursor, Grok, CodeWhale. No: Gemini, ZCode, OpenCode, Kilo, Pi, omp, Windsurf, Cline, Antigravity, MiMo. Idea I-98 (Copilot `subagentStop.modifiedResponse`).

Check result: §23 lists every host with a source; follow-up tasks only for the two yes rows.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
