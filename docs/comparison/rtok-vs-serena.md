# rtok vs serena-agent 2.0.0 (PyPI `serena-agent`, LSP backend rust-analyzer 1.90.0)

Measured with `bench/c14/run_b3.py` in image `rtok-c15:3` (`bench/c14/b3.Dockerfile`: `rtok-c14b:2`
plus rustup 1.90.0 with `rust-analyzer`, which serena needs). rtok built from
`12ebeb972e2bea88bec939a9ca9b436db884de13`, input repo checked out at the same commit. Each case runs in a
fresh copy of the repo with a fresh `HOME`. Indexing and seeding happen in an untimed setup step on the same
MCP session. Then come the measured `tools/call`s: 1 warmup + 3 runs, median tokens of the text the model
receives (tiktoken `o200k_base`). Kept = share of the expected anchors literally present in the answer:
the `.rs` files from `git grep -w <sym>` for callers, item names for outlines, seeded fact ids for memory
search. "non-deterministic" means more than 2 tokens of spread across fresh runs; for codebase-memory-mcp
that comes from the temp checkout path inside its qualified names.

Tool catalog (`tools/list`, compact JSON): 21 tools, **5612 tokens** (rtok's own: 2910 tokens for 40 tools). Through `rtok mcp -- <server>`: 5612 tokens (passed through unchanged).

| Case | Raw | rtok | serena |
|---|---:|---:|---:|
| `callers:estimate` | 6003 | 1642 (72.6 %, kept 88 %, 418 ms) | 6601 (-10.0 %, kept 35 %, 21615 ms) |
| `callers:register_mcp` | 4526 | 1428 (68.4 %, kept 72 %, 391 ms) | 508 (88.8 %, kept 3 %, 20318 ms) |
| `outline:src/plugins/json_tree/mod.rs` | 10154 | 594 (94.2 %, kept 96 %, 124 ms) | 203 (98.0 %, kept 59 %, 1273 ms) |
| `symbol:estimate` | 8699 | 424 (95.1 %, kept 100 %, 414 ms) | 755 (91.3 %, kept 100 %, 4281 ms) |
| `explore:wrap foreign MCP server tools list` | — | 1810 (—, kept 0 %, 626 ms) | n/a |

serena's default `claude-code` context exposes 2 tools (`initial_instructions`, `serena_repl`, 1032 tokens).
Measured with `--agent-interface tools` (21 tools, 5612 tokens) so each operation is one call.
rust-analyzer starts cold in every fresh run, so the first semantic call takes 4–22 s.

Verdict: serena is exact rather than textual. `find_referencing_symbols` resolves one definition, so for
`register_mcp` (32 definitions) it reports only the callers of `claude::register_mcp` (3 % of the grep files,
which is correct for that symbol). For `estimate` it returns 6601 tokens of `content_around_reference`.
Its outline is the smallest (203 tokens) but groups names by kind without lines, keeping 59 % of item names.

What rtok should borrow: nothing for token cost. rtok already offers `backend = "lsp"` for exact
references. The kind-grouped outline (`{"Function": [...]}`) is worth a look as a terse mode.
