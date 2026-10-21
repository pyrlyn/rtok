Found 2026-09-22 in the docs pass (all confirmed against the files): T126 appears as three table rows and three identical cards (plan.md `### T126` ×3); T123 has two full cards; `todo.md` carries T126 twice; T183's Check sits under T184's card (the `Check: dry-run with \`all\`…` paragraph after T184's own Check) so T183 has none and T184 appears to have two; three blank lines split the task table into four markdown tables that render as raw pipes on GitHub and the site; T122 still carries the fix scope handed to T127; and T122/T123/T125 — all `src/` code cards — are claimed by `claude-haiku-4-5`, the exact models AGENTS.md forbids for code ("on T122–T125 every Haiku code diff had a defect its report called green"). "One task = one card with a Check" is broken throughout.

Plan: delete duplicate rows/cards (one T126, one T123), move the stray Check into the T183 card, remove the blank lines inside the table, trim T122's card to what T127 does not own, dedupe `todo.md`, and reassign T122/T123/T125 to a mid-tier model (T126 is docs-only and may stay). Docs only.

Check: `tests/plan_unique_ids.rs` — every `| T… |` row id and `### T…` heading unique, exactly one `^Check:` per card, no blank line inside the task table, no low-cost model on a card whose Plan touches `src/`; `grep -c '^### T126\.' plan.md` = 1 and `grep -c 'T126\.' todo.md` = 1; `just site` builds.

Result: Removed the merged T122/T123/T125/T126 rows and cards (7 cards, 6 rows) and their todo.md lines (done.md already had them), the duplicate T163 todo row and the blank lines splitting the task table. New tests/plan_unique_ids.rs: unique row/card/todo ids, one contiguous task table, exactly one Check: per card (T184/T235/T246.3/T246.4 listed until their owners fix them), no haiku-owned card touching src/. Fails on the old plan.md naming T123/T126/T163 and the split table.

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
