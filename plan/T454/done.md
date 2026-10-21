From the repowise comparison (2026-10-08). Five algorithms, no new plugin.

1. `cmd` `Measurement` estimates use the first 30 000 chars the host keeps (`bounded::host_visible_prefix`). Byte lengths stay the real sizes.
2. `family_of` peels a leading `FOO=1`, `uv run` / `uvx` / `npx` / `pnpm exec|dlx` / `yarn dlx` / `poetry|pipenv|hatch run`, `python|python3|py -m`, and `cmd /c`, at most four times, the same way it peels `mise` / `just`.
3. `symbol` accepts `names`. One name is the old text; several are headed `= name` and capped together. An unknown name is a line.
4. `symbol` / `callers` / `impact` / `explore` prepend `index <oldsha> head <newsha>` (full `rev-parse` hex, no timestamp) when the stored HEAD differs and the index is not rebuilt. `outline` does not. A non-repo adds nothing.
5. `worktree add` copies `symbols` rows from the main checkout. The next index skips identical blobs (`indexed == 0`) even when mtimes differ. A copy error is a warning.

Check: `cargo nextest run -p rtok -E 'test(host_visible) | test(emit_filtered) | test(family_names) | test(copy_symbol_rows) | test(stale_head) | binary(graph_contract) | binary(graph_scope)'` — 23 passed. Formatter, bounded, surface, and worktree tests — 94 passed. `cargo clippy -p rtok --all-targets -- -D warnings` clean. `cargo fmt --check` clean. `tests/trycmd/mcp.toml` and `report-md.toml` re-blessed for the shorter `symbol` description (`~798` desc tokens) and the `names` schema.

Result: 2026-10-08. No new dependency. No saving claim beyond the corrected `Measurement` rows.

`llms.txt` is a markdown link index with a `.txt` name, so `read` mode `map` used to fall through to the unknown-language line scan and drop the `- [title](url)` rows. `outline::llms_links` parses those rows (fenced blocks skipped) and `render` appends `link <title> <url>` after the heading map for `llms.txt` and `llms-full.txt` only. URLs are not fetched. A normal `.md` file is unchanged.

Check: `llms_txt_map_lists_links_and_skips_fences`; `just check`.

Result: map of a fixture `llms.txt` lists the two real links and omits the fenced one; `doc.md` has no `link ` rows.

A tool call now stores one scrubbed observation (title, type, narrative ≤ 400 characters, existing root-relative files). The raw output stays in the archive. The same session, tool and narrative within 5 seconds does not insert again. `mem_*` tools are not observed. `mem_get` with `obs` returns the narrative.

`notes.uses` and `last_used` move on a live `mem_get` and on a prompt-recall injection. Recall order is pinned, then retention score, then newest id. A low score is never a delete.

`prompt_recall` fuses observation FTS with file-linked observations (RRF, k = 60), keeps at most 3 hits from one session, and appends at most two `obs <id> <title> (<session>)` lines inside `recall_tokens`. `PreCompact` adds the title index and this session's observations when either exists, and stays `{}` when both are empty.

Check: `cargo test --lib plugins::memory`; `cargo test --lib schema_matches_the_migrated_tables_and_snapshot`.
Status: done 2026-10-08
Model: Cursor / grok 4.7

`rtok graph review` (CLI only, same flags as `affected`) scores each changed file from the symbol index: an untested definition is 0.30, a tested one is 0.05, a whole-token security keyword adds 0.20, and callers add at most 0.10. It prints the level, the untested names, and the hunk ranges that fit an 800-line budget. No sixth MCP tool. No new crate. No `Measurement` unless `cap` shortens the answer. The repo `.coderabbit.yaml` stub is removed so the central pyrlyn config applies. A pull request uploads the report from an unprivileged job; a `workflow_run` on the default branch posts the sticky comment and does not check out pull-request code. A high score does not fail CI.

Check: `cargo test --lib plugins::graph::review`; `graph_surface_is_five_tools_under_150_tokens`; `just check`.

Result: 8 review tests passed; the MCP surface stays five tools. `just check` ran the full gate (trycmd snapshots are a shared input): 2978 passed, 6 skipped.

`prompt_recall` ranked with FTS only while `mem_search` used `search_notes_hybrid` when `embed.enabled && embed.hybrid`. The hook now uses that same RRF over FTS and vectors already stored (`Store::search_notes_hybrid_stored`, on `Notes` / `Ctx` with an FTS default). It does not call `embed_stale`: a missing or stale row is not rewritten. An empty embedding table returns the FTS list, so the title index matches the flag-off path. `files::recall_hits` still wraps that list. Titles and ids only. One `Measurement` row, kind `prompt_recall`. Embeddings stay off by default. No change to `src/plugins/graph/`, `src/hooks/`, `src/proxy/`, or the `Plugin` trait. Amends D35: a stored-vector read is allowed on the hook only when embeddings are already on.

Check: `mise exec rust -- cargo test --lib -- plugins::memory store::embed` (66 passed). `mise exec rust -- cargo clippy --all-targets -- -D warnings` clean. `mise exec rust -- cargo test --test latency --release` missed 10 ms on this host for every event, including PreToolUse (p95 13.8 ms, min 6.7 ms, `--test-threads=1`), which this change does not touch — the same spawn-floor miss T428 records on a loaded host.

Result: lib tests 66 passed; clippy clean; `tests/trycmd/config-init.toml` matches the new `hybrid` comment. #894.
Status: done 2026-10-08
Model: Cursor / grok 4.7
