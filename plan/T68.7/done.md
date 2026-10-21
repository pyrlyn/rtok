From the codegraph / graphify review. graphify tags every edge `EXTRACTED` / `INFERRED` / `AMBIGUOUS`; codegraph marks heuristic bridges `provenance: heuristic`. rtok's tags backend resolves by name: `callers(new)` on a repo with twelve `new` definitions merges them all and the model cannot tell.
Done when `callers`, `impact` and T68.1 append ` ?` to a reference line whose name has more than one definition in the root (`symbol_defs` count > 1, one query per distinct name, cached per call), the answer's first line says `N names ambiguous (?): narrow with path or kind, or backend = "lsp"` when any is, the LSP backend never marks (its resolution is exact), unfiltered contract strings for names with one definition stay byte-identical, unit test with two `new` definitions and one `alpha`.

**Result (2026-09-18).** Commit `45c4531`. Ambiguous names marked with ` ?` on `callers`, `impact`, and `explore`; banner line when any name has multiple definitions.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
