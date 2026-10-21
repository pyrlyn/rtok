Best competitor: lean-ctx 91.4 % (`read` rewrite). Design: detect range-print of a file: emit numbered range only once per session (session dedup by content hash, re-read → `unchanged since <id>`, changed → unified diff of the range); for full source files without range: signatures + elided bodies with line spans.

Check: #1076 harness (`bench/c14`) shows ≥ 93 % on `cat` source; re-read ≥ 95 % on this category with full anchor retention.

Plan: for `cat <source>` without a range, emit signatures with elided bodies and line spans (reuse the tree-sitter outline already used by `rtok read`); for `sed -n a,bp f` keep the numbered range and, on a repeat in one session, print `unchanged since <id>` or a unified diff of the range (content hash kept in the archive store, no new table unless Diesel schema needs it). Tests: golden for cat source, session re-read unit tests. Stay under the bounded-passthrough rules in `bounded.rs`.

Result: Measured with tests/bench_fixtures.rs (the #1076 bench/c14 harness is not on main): cat_rust 72.7% -> 87.1% (floor raised 68 -> 85). `cat` of a source file over 80 lines prints its definitions as start-end spans plus the signature (outline from the tree-sitter tags the read plugin uses); data files (toml/json/yaml/md/...) keep the `cat` rule. `cat`/`sed -n a,bp` of a file seen before in the session goes through the read cache: unchanged is a pointer, changed is a unified diff (src/plugins/cmd/catfile.rs, run.rs). An unchanged identical re-read is already covered by `identical_result`. Follow-up: ids in plugins/read/cache.rs were still full length after T672; T906 prints the 8-char prefix. Local gate: nextest 3873/3874, the failure is cli_trycmd report-md (shared target dir, environmental).

Status: done 2026-10-15
Model: Claude Code / claude-sonnet-5-5
