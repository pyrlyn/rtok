Merged as T454 (pyrlyn/rtok#898); renumbered to T474 because #888, opened earlier, also took T454.

Ideas only from jCodeMunch (no code, schema, or comments copied; no MCP tool, embeddings, or second copy of source files). `symbols` keeps `start_byte`, `end_byte`, and `content_hash` (sha256 of that slice). A definition longer than `body_lines` archives the uncut span and ends with `expand <id>`. Every definition head prints `{path}::{name}#{kind}@{line}`. `symbol` takes optional `id` and, when set, returns that one row. `symbols_fts` indexes definition name, signature (`line_text`), and the contiguous `///`/`//!` doc above the line (512 bytes). `explore` falls through to that index when a name does not resolve, ranking a name-token hit above a signature hit above a doc hit.

Check: a body over 40 lines round-trips through `expand`; a shorter body is unchanged aside from the id prefix; an unchanged mtime and size is still skipped; `symbol` with an id returns that row; `explore` of `truncated source lines` includes `body_lines`; `cargo test --test graph_contract --test graph_model` and the `outline` / `index` / `graph` unit tests pass; `just check`.

Result: `symbol` prints the id on every definition head and reads a cut body back from the file span when the file sha matches. `rtok expand <id>` returns the uncut span. `explore` of `truncated source lines` on the graph plugin includes `body_lines`. `just check`: 2978 passed, 6 skipped.
Status: done 2026-10-08
