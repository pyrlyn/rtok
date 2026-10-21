From the codegraph / graphify review (2026-09-18). codegraph's single `codegraph_explore`
answers a free-text question with symbols' source, call paths between them and a
blast-radius line (88 % fewer tool calls claimed, unverified); rtok needed three to five
calls (`symbol`, `callers`, `impact`, `read`).
Done when a fifth MCP tool `explore(query, path?)` resolves the query's identifier tokens
(exact, else prefix best-5 by reference count), prints each definition body once per file,
the call paths between the resolved symbols (depth ≤ 3) and one impact depth-1 line per
symbol; tags and LSP backends share one assembler; the answer goes through `cap` with an
archive id; description ≤ 60 tokens, surface ≤ 150; `tests/graph_contract.rs` pins one
two-symbol question byte-exact; `Measurement { plugin = "graph", kind = "explore" }`
records bytes returned versus the sum of the calls it replaced.

**Result (2026-09-18).** Commit `96272ca`. `explore(query, path?)` splits the question into
identifier tokens (`explore_tokens`: alnum/`_` runs, deduped, first 8), resolves each
exactly, else `Store::symbol_name_prefix` (distinct def names, best 5 by reference count,
ties by name). Both backends implement one `ExploreParts` trait — `TagsExplore` over the
indexed rows, `LspExplore` over workspace/symbol + callHierarchy — and
`assemble_explore` produces one answer shape: `= name` + definition bodies (via the
`symbol` text builders, extracted as `defs_text` / `symbol_text`), `paths:` with caller
chains (`symbol_paths`, recursive CTE over the `scope` edges, simple paths only, ≤ 3 hops;
T68.4 reuses it for `impact --to`) or `none`, then `impact:` with one `name ← N` depth-1
line per symbol. The answer goes through `cap_kind` like the other tools; the
`kind = "explore"` Measurement's `before` is the exact bytes the replaced `symbol` +
`impact` calls would have printed. The SDK `Symbols` trait gains `symbol_name_prefix` and
`symbol_paths` as defaulted methods (D25 additive pattern; `MemoryHost` needs nothing);
`Runtime` delegates to the store. The `path` filter narrows printed definitions and the
impact counts, same as `symbol`'s filter; paths stay cross-file like `impact`'s walk.

Surface re-measured: 5 tools, 127 description tokens, each ≤ 60
(`cargo nextest run -p rtok graph_surface`, 2026-09-18) — still ≤ 150. Whole MCP surface
re-measured with `rtok doctor`: 12 tools, ~223 desc tokens (was 11 / ~143); the rows in
`docs/comparison.md` §2/§4, `research.md` §2/§9.3/§14 and the README graph paragraph were
updated to the new numbers. Deviations: 13 files — over the 3-file guide, noted in the
commit; the LSP backend shares the assembler but has no live test (no language server in
CI — same as the other `lsp.*` tools).

Check: isolated worktree at `458f36c` + the 8 code/test files (main tree was red from
concurrent agents' T60.10/T55.15 edits): `cargo clippy --workspace --all-targets
--all-features --exclude rtok-wasm-demo-guest -- -D warnings` clean; `nextest graph::`
57/57; `--test graph_contract` 5/5 (incl. the byte-exact two-symbol question and the
path-filtered case); `--test extra_cover` 9/9. One unexplained one-off flake of the
contract test on a freshly linked binary during an earlier run; 5 consecutive re-runs
green — disclosed, not chased.

---
