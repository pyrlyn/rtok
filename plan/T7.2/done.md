**T7.2 instruction audit** · T1.4 · `src/doctor.rs`
Do: `rtok doctor --instructions`: token count of `~/.claude/CLAUDE.md` + project CLAUDE.md + every enabled plugin's SessionStart text (lean-ctx, engram, ponytail, claude-mem, token-optimizer today); flag duplicates (same sentence in two files) and anything > 1,000 tokens.
Check: on this machine, report lists ≥ 4 injectors and their token totals.
Status: done 2026-09-02 · Check: `instructions_lists_four_injectors` — four named MCP injectors plus CLAUDE.md files, each with a token total; duplicates and >1000 flagged. `make check` green.
