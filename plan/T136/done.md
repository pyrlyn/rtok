Gate for I-82 (deny a native Read of an indexed source file). §2: Read is 15 % of tool-result tokens and the eight largest results are all whole-file Reads of 38–68 K chars. I-82 is parked on exactly this missing number.
Plan: in `src/measure/stats.rs`, a `read_whole` row: native `Read` calls with no `offset`/`limit`, on a path whose extension has a tree-sitter grammar in the graph plugin, result ≥ the outline threshold; bytes and share of Read bytes and of all tool-result bytes; how many were followed by an Edit of the same path within the guard window (those needed the body). Reuse the grammar list and read-tool detection — no copies.
Check: fixture test; dated `rtok stats --since 30d` row in `research.md` §2. Reads not followed by an Edit ≥ 5 % of tool-result tokens → I-82 goes to the creator with the number; below → I-82 closes with it.

Do (2026-09-24): `measure::stats` gains a `read_whole` row. A native `Read` counts when it has no `offset`/`limit`, its result is at or above `[plugins.read] native_max_bytes`, and `read::outline::supported` has a grammar for the path (feature-gated on `read`); it counts as edited when an Edit/Write/MultiEdit of the same path follows within `[plugins.guard] window_turns`. Fixture test `read_whole_counts_large_unranged_reads_of_outlined_files`. `rtok stats --since 30d` (367 sessions): 6 calls, 216 364 B, 1.5 % of Read bytes, 0.3 % of tool-result bytes, none edited after — under the 5 % gate, so I-82 moves to Rejected with the number; dated subsection in `research.md` §2.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
