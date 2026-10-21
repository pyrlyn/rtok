**T61.3 `doctor` skill audit** · T63.1, T71.3 · `src/doctor.rs`, `src/report/ai.rs`, `src/report/pdf.rs`
Do: `rtok doctor` gains a `skills` section — one row per listed skill over the documented §10.1 roots (`~/.claude/skills`, `.claude/skills`, `.agents/skills`, the codex/cursor/gemini/copilot user roots, and `<installPath>/skills [id]` of every enabled plugin from `installed_plugins.json`), with description chars, body bytes after frontmatter, invocations in the last 30 d counted the T61.1 way (`-` when no scan ran), and the measured flags `desc>200` / `body>8K (references/)` / `never invoked` (only when invocation data exists) — plus the one-line total; advice only; `doctor --json` carries the same rows.
Check: `skills_audit_flags_the_measured_warns_on_a_vfs_tree` (Vfs tree, four skills, flags + `plugin:<id>` source + fail-open with no data) and `skills_audit_renders_a_section_with_flags` on `to_text`.
Complexity: 2/5 — one probe, one pure row builder, one render section.
Status: done 2026-09-18
Check result: doctor tests green; full `just check` green in the isolation worktree. Deviation: the `docs/` doctor page does not exist — the README's abridged `rtok doctor` example carries the section with the measured totals instead.
Model: ZCode / GLM-5.3-Flash
