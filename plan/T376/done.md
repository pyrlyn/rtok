From the Empryo study (idea-only, clean-room; Empryo's intelligence router tries LSP, then tree-sitter, then regex per call and records which one answered). With `plugins.graph.backend = "lsp"`, `symbol_filtered`, `callers`, `impact`, `outline` and `explore` (`src/plugins/graph/mod.rs` around `:260`, `:390`, `:441`, `:897`, `:1015`) return `lsp::…` directly; a server that is missing, still indexing past `READY` (40 s, `src/plugins/graph/lsp.rs`) or dead turns the call into an error instead of a tags answer (to verify: whether `lsp.rs` already degrades internally).

Plan: one wrapper in `mod.rs`: try `lsp::<op>`; on `Err` or an empty answer for a name the tags index has, run the tags path and prefix `(tags; lsp: <reason>)`. Record a `Measurement` (`plugin: "graph"`, `kind: "lsp_fallback"`). No retry loop; the existing restart logic in `lsp.rs` stays.

Done when: with `backend = "lsp"` and no server on PATH (or a fake that exits), `symbol <name>` returns the tags answer with the fallback prefix.

Check: test with the fake/absent server for each of the five ops; existing LSP tests unchanged; `just check`.

Execution plan: (1) `src/plugins/graph/mod.rs`: `lsp_or_tags` wrapper (backend check, `Err` or none-answer for a name `tags_know` finds, prefix, `lsp_fallback` Measurement); each of the five tools keeps its tags body as `<op>_tags` and routes through the wrapper, the outline path guard stays outside it. (2) Tests in the same file: a manifest-less root makes `lsp::*` fail before any spawn, so no PATH access; plus direct wrapper tests for the empty-answer rule. (3) `just check`, then move the card to `done.md`.

Result: with `backend = "lsp"`, `symbol`, `callers`, `impact`, `outline` and `explore` go through one `lsp_or_tags` wrapper in `src/plugins/graph/mod.rs`; an `Err` or a none-answer for a name `tags_know` finds returns the tags answer headed `(tags; lsp: <reason>)` and records `graph` / `lsp_fallback`. Tests: `lsp_backend_falls_back_to_tags_for_every_tool`, `lsp_empty_answer_falls_back_only_for_a_known_name`.
