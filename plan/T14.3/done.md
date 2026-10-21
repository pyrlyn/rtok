**T14.3 `read` design** · T14.0 · `src/plugins/read/PLAN.md`
Do: survey file-reading strategies: lean-ctx modes and token-optimizer's structure map, plus at least one outside source (aider's tree-sitter + PageRank repo map, ctags, or an LSP outline). Answer: for the first look at an unknown repo, does a ranked repo map beat per-file signatures, and is the ranking worth its build cost at v0.1? Decide the mode set (`full` / `lines` / `map` / `signatures`) and the re-read dedup contract.
Check: shared Check; `Target:` is tokens for “understand this repo” on a fixture repo vs the retired stack.
Status: done 2026-09-02 · `src/plugins/read/PLAN.md`. Modes full/lines/map/signatures; no PageRank in v0.1. Target: below lean-ctx 3.1 K/turn (P4).
