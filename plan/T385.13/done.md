optimization.md §5 ("Not built; measure first"). From `calls`: how often the same file content is read in more than one session within a day, and the bytes involved. Measured 2026-10-08 (window 2026-10-05 to 2026-10-08, 38 sessions, 3,049 reads, 13.6 MB; same content = equal SHA-256 of the returned text, within a day = same UTC day): 38 cross-session duplicate reads, 56,561 B (about 14,140 tokens), 0.42 % of the bytes read, 0.00034 % of input counted once and 0.25 % to 0.60 % of input resident-weighted (input 4,139,214,544 tokens, main plus sub-agents). Cross-checks over 25 days (MCP read, 0.28 %) and via `read_cache` (0.84 % of bytes) agree; keyed on path plus content only 2 reads repeat, because worktrees give the same file different paths. Under the 1 % gate, so no build task; the optimization.md §5 row records the number.

Check: the dated `research.md` row (§36).
Result: cross-session read duplication is 0.25 % to 0.60 % of input, below 1 %; not built (research.md §36). #838.
Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
