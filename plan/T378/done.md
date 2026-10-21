From the Empryo study (idea-only, clean-room; Empryo `trigram.ts`: per-file trigram sets, candidate files = intersection of the query's literal trigrams). `search` (`src/plugins/read/search.rs:92`) walks and scans every file. Gate: I-95 (parallel walk) measures `search` p95 on a large repo first; if it is ≤ 200 ms, close this card with the number.

Plan: per-root trigram posting lists (`file_id` bitmaps, `roaring` only with a `Cargo.toml` justification, else sorted `Vec<u32>`) built in the graph index pass for text files ≤ 1 MB; queries with a literal of ≥ 3 bytes scan only candidate files; regexes without a usable literal take the full scan.

Done when: a literal `search` on a 50k-file repo scans only candidate files and returns the same hits.

Check: equivalence test (prefiltered vs full scan) over a fixture; divan bench `search` p95 −50 % on the large repo; index size growth recorded in the card; `just check`.

Execution plan (2026-10-12): measure `search` p95 on the largest local repository with a dated command; at or under 200 ms close the card with the number in `research.md`; over 200 ms report back before building the prefilter.

Progress (2026-10-12, `research.md` §39): `search` through `rtok mcp` at bf88f07e2, load ~20. Real repos top out at ~2k files (apps/stator 1,995): p95 ≤ 157 ms. A synthetic 66,493-file tree (local repos cloned 7×): capped queries ≤ 137 ms, an absent literal p95 30.4 s, linear in file count. No real ≥ 50k-file repo here. Creator decision: build the prefilter on the synthetic number, find a real 50k-file repo first, or close at the 2k scale.

Result: Closed by its gate without a prefilter (creator: decide as best, 2026-10-12). `research.md` §39: `search` through `rtok mcp` at bf88f07e2 has p95 ≤ 157 ms on the largest real local repos (~2k files, apps/stator 1,995). No real ≥ 50k-file repo exists here; a synthetic 66,493-file tree shows capped queries ≤ 137 ms and an absent literal at p95 30.4 s, linear in file count. A per-root trigram index is not justified at the sizes rtok runs on; the large-tree cost is noted on I-95 (parallel walk) as the cheaper first step.

Check: Dated row with binary commit, roots, file counts, queries, runs, p50/p95 and commands; no product code.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
