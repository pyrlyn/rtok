Found 2026-10-02: 15 `cli/run: unknown archive id` errors for `-` (11), `x` (2) and `/dev/stdin` (2). Claude Code transcripts show the pattern `cat file | rtok expand - 2>/dev/null || true` and `gh pr list … | rtok expand - || gh pr list …` — agents try to get unfiltered output and guess a stdin form of `expand`.

Done when: the skill documents the supported way to see a command's raw output (the archive id from the footer, or a raw/bypass flag if one exists), and `rtok expand` with an argument that cannot be an archive id prints a one-line hint pointing there instead of a bare `unknown archive id`.

Check: trycmd case for `rtok expand -`; skill stays within its budget; `just check`.

Execution plan: (1) `rtok expand <arg>` where `<arg>` cannot be an archive id (`-`, `/dev/stdin`, anything not hex) fails with a one-line hint: the id is the hex in the `expand <id>` trailer, and there is no stdin form; (2) `skills/rtok/SKILL.md`: one line that the trailer's id is the only way to the raw output (no stdin pipe); (3) trycmd case for `rtok expand -`; `just check`.

**Result (2026-10-02).** #611: `expand::unknown_id_message` — a non-64-hex id (`-`, `/dev/stdin`, `x`) gets `(expand takes the hex id from an "expand <id>" trailer; it does not read stdin)`; a well-formed unknown id keeps the plain message; CLI and MCP `expand` share it. Skill line added. Tests: `non_id_gets_the_trailer_hint_and_a_hex_id_does_not`, `tests/trycmd/expand-stdin.trycmd`.

Status: done 2026-10-02
Model: Claude Code / claude-sonnet-5-5 (reviewed by Claude Code / claude-opus-5-5)
