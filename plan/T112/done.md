**T112 Restart prompt spinner no longer erases the typed answer** · `src/agents/restart.rs`
Do: the `[y/N]` restart prompt after `agents install|uninstall` redrew its spinner every 80 ms with `\r\x1b[K` + the prompt, wiping the terminal echo of what the user was typing. Now the row is cleared once on the first paint; each frame swaps only the column-0 glyph under cursor save/restore (`ESC 7`/`ESC 8`); an answered prompt blanks the glyph one row up instead of reprinting the prompt, and an unanswered one ends the line.
Check: `agents::restart` tests assert exactly one `\x1b[K` per prompt, in-place glyph frames, one trailing newline on timeout, and the answered-row cleanup; `just check`.
Complexity: 1/5 — three small write helpers and test updates.
Status: done 2026-09-21
Check result: `agents::restart` 13/13 pass. Known limit: an answer that wraps past the terminal width puts the glyph on the wrong row (cosmetic).
Model: Claude Code / claude-opus-5
