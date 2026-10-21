Found 2026-10-02 in `~/.rtok/errors.log`: `read` rejects `invalid line range \`N,N\`` (14) and `\`N, N\`` (3) — T172 only stripped quotes; `search` fails with `missing \`pattern\`` (8), `read` with `missing \`path\`` (2), `symbol` with `missing \`name\`` (1); `outline` says `No such file or directory (os error 2)` (6) without the path.

Done when: line ranges accept `a,b` and `a, b` like `a-b`; a missing-param error names the keys the call did send (look up the logged calls to see whether one alias, e.g. `query`, covers most of them — add only aliases the logs show); file errors name the path.

Check: MCP unit tests for each case; `just check`.

Execution plan: (1) the line-range parser used by MCP `read`/`expand` accepts `a,b` and `a, b`; (2) missing-param errors in `src/mcp.rs` list the keys the call sent; add an alias only where the logged calls (store `call_io`) show one; (3) `outline`/`read` file errors name the path; (4) unit tests per case; `just check`.

**Result (2026-10-02).** #606: `parse_range` reads `a,b`/`a, b` as `a-b`; `prepare_args` in `src/mcp.rs` (one place for `call_tool` and `call`) maps `search`'s `query` to `pattern`; missing-param errors add `(got: <keys>)`; `read`/`outline` file errors name the path without the `(os error N)` tail. Tests: `parse_range_accepts_comma_pairs`, `search_accepts_query_and_missing_keys_name_what_was_sent`, `file_errors_name_the_path` (asserts only the path suffix — OS wording differs on Windows).

Status: done 2026-10-02
Model: Claude Code / claude-sonnet-5-5 (reviewed by Claude Code / claude-opus-5-5)
