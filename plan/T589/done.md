Confirmed with actual [Follow::poll](src/log.rs#L399): matching first lines and sufficient line count make a rotated replacement look like the previous file. Prime a reader with `same\nold-shown\n`, append `old-unseen`, rotate to `.1`, then create `same\nnew-unseen\n`: polling returns nothing, losing the old tail and both replacement rows. A second manifestation occurs when the replacement exists but is empty: polling carries the old tail and resets `first` to None; after the replacement receives its first line, polling replays the whole rotated file, including previously shown rows. [State replacement](src/log.rs#L430) loses the generation boundary. T24.3 covers ordinary rotation, missing-path gaps and storms with distinct first lines, not either fixture. These are one identity/state defect, not two cards.

Execution plan:

1. Add deterministic follow tests for matching first lines across rotations and an empty or partial-line replacement between polls. Assert every complete line is returned once in chronological order, including the unread tail of the old file.
2. Track file generations with a reliable filesystem identity or retained file handle and consumed position, using the existing platform abstractions where possible. Do not infer identity from log text; retain the carry boundary after observing an empty replacement.
3. Cover consecutive rotations, retention exhaustion, missing/unreadable intervals, truncation and partial writes. Preserve the shared initial-screen snapshot contract and define any unavoidable loss when the old generation has already been deleted.
4. Exercise watch integration with a scratch rotating writer and rerun the existing rotation/first-screen tests on supported platforms.

Check: retained generations neither lose nor replay complete rows in either fixture, existing follow ordering remains intact, and `just check` passes. The offline probe compiled actual Follow and whole-line functions with local scratch files; full CLI/watch scheduling was source-traced, not executed.

**Continued source audit (T540).** T541–T546 were confirmed against source at
`efbb220b` (product code unchanged from `0b14b88e`). Fix the disk data-loss
case T541 first. T542/T543 concern index freshness; T544/T545 concern filtering
and should share eligibility logic; T546 concerns the derived rank document.
Completed T68.10/T35.5 and active T370 describe intended behavior, but do not
track these concrete failure cases. These cards record defects, not implemented fixes.

Result: `Follow` in `src/log.rs` tracks generations by file identity (`same_file::Handle`) and keeps the old file open, returning its unread tail across rotation, retention and an empty replacement; truncation in place restarts from the top.

Check: `log::` 23 and `--test logs` 9 green. New dependency `same-file` (already in the lock via `ignore`).

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
