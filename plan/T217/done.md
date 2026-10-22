Found 2026-09-22 in the docs pass: `AGENTS.md` instructs "Keep this file under 350 tokens; it is loaded into every session" and is ~7 KB / ~1,100 words — the "Rules that never bend", "Models" and "Testing" sections alone exceed the budget. Every session in every project pays several times the promised injection, the exact per-turn overhead rtok exists to reduce.

Plan: trim to a true ≤ 350-token core (What / Read first / Workflow / Rules / Models one-liners) and move the Testing section and rule expansions to `plan.md` → Working agreement or `CONTRIBUTING.md`, keeping pointers. Creator sign-off before the trim lands (it rewrites the file every agent loads).

Check: a `just check` assertion on an approximate token count (words × 1.33 < 350, or a tokenizer count in a small test) that fails on `main` today and passes after the trim; `CLAUDE.md` still symlinked to the same file.

Do (2026-09-23): `AGENTS.md` trimmed from ~1 020 words to a What / Read first / Workflow / Rules / Models core (183 words, 1 298 B); the Testing section and the rule expansions moved to `CONTRIBUTING.md`. The creator asked for this change to land. Check: `tests/host_docs.rs::agents_md_stays_under_its_350_token_budget` (words × 4/3 and bytes / 4 both < 350; `CLAUDE.md` still a symlink to `AGENTS.md`) green.
