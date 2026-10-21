**T38.6 translate all `.md` to English** · P3, 1/5 · `toolchain.md`, `plan.md`, `AGENTS.md`
Do: a repo-wide grep for Cyrillic hit three project files — `toolchain.md` (headers + every row), one line in `plan.md` (claiming rule), one line in `AGENTS.md` (Workflow). Rewrite all three in English: parent-spec table headers with English cells, `Status`/`Agent`/`Complexity` in the two one-liners. Nothing else changes.
Check: a repo-wide grep for Cyrillic across `*.md` finds no matches.
Complexity: 1/5
Status: done 2026-09-14 · Model: OpenCode / Muse Spark 1.3
Evidence: `grep '[Cyrillic] --include='*.md'` — no matches (the only hit during work was the Check line quoting the pattern itself, reworded).
