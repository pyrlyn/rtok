From the Empryo study (idea-only, clean-room). `impact` past `plugins.graph.impact_tokens` (default 1500) groups the BFS result by file, orders files by (depth asc, stored T370 file rank, ref count desc), prints `path (N refs, depth d)` with the first 3 lines per file and stops at the budget with `+K files, M refs not shown — impact <name> --all`. The budget covers the finished answer (ambiguity banner and marks, other-definitions and co-change lines come off it first). `--all` (CLI) / `all` (MCP) and `impact_tokens = 0` keep the flat listing; a listing that fits is unchanged; the LSP backend is unchanged; in a multi-project scope the order falls back to depth then ref count. A cut records a `Measurement` (kind `impact`).

Check: unit tests on a synthetic hub (fits, cut line, ordering by depth/rank/refs, marks and overhead, `all`/0 flat) and on an indexed 60-file hub; trycmd goldens (completions, help, mcp, config-init/show, report-md); `just check`.
Result: blast + graph + impact tests 122 passed; `just check` exit 0 (nextest 2765 passed, 8 skipped); on this repo `graph impact estimate --depth 2` is ≈1470 tokens ending in the cut line. #844.
Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
