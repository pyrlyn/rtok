**T14.9 `graph` design** · T14.0 · `src/plugins/graph/PLAN.md`
Do: survey code-structure indexes: the four retired graph servers plus at least one outside source (ctags, an LSP call-hierarchy, or aider's repo map). Answer: what tree-sitter-tags cannot see (dynamic dispatch, macros, generated code), whether that is acceptable at v0.1, and which three tools are worth their description tokens. Decide the index refresh trigger and the output cap.
Check: shared Check; `Target:` is MCP description tokens plus a hit rate on a fixture symbol set.
Status: done 2026-09-02 · `src/plugins/graph/PLAN.md`. Three tools; tags index; LSP v0.2. Target: P8 description tokens + < 2 s index.
