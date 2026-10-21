Best competitor: rtok leads. Design: keep dir grouping but print full paths for ≤ 50 hits so the anchor check passes.

Check: #1076 harness (`bench/c14`) shows 100 % paths, ≥ 69 % on this category with full anchor retention.

Plan: in the find formatter print full paths for ≤ 50 hits (grouping only above that); fixture test asserting 100 % paths and fewer tokens than raw; measure on `bench/fixtures`.

Outcome: `group_dir` keeps lists of up to 50 `dir/name` hits verbatim, so a small `find` keeps 100 % of its paths; above 50 it folds as before. Pinned by `group_dir_keeps_full_paths_up_to_fifty_hits` and the regenerated find/rg/awk goldens (docs in en/ru/uk). Measured on the repo fixtures: the #1076 `bench/c14` harness is not on main.
