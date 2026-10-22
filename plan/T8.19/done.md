**T8.19 `graph_truth` is red and nobody noticed** · T8.14 · `tests/graph_truth.rs`, `src/plugins/graph/index.rs`
Do: `cargo test --test graph_truth` fails — `definition precision 0.864: a name resolved to a file
it is not defined in` — and it failed identically at `ddda5d0`, so it has been red for at least a
day of committed work while `just check` was still being reported green. Find out which: the index
regressed and the labelled truth is right, or the truth file lists references the tree no longer
has. Fix whichever is wrong; if the labels are the stale half, say so in the Check result rather
than editing them quietly.
Check: `cargo test --test graph_truth` passes, and the run prints the precision and recall it
passed at, so the next regression names a number instead of a threshold.
Complexity: 3/5
Status: done 2026-09-09 · Model: GLM-5.3 (subagent)
Check result: green — the labels were the stale half, edited openly. Definition recall was already
1.000 (38/38); precision 0.864 was 38/44 because all six extra def rows were genuine definitions
(`mark_symbols_stale`, `symbol_defs`, `insert_note`, `put_read_cache`, `get_read_cache`,
`put_archive_decision`) in `crates/rtok-plugin-sdk/src/testing.rs` — real `impl MemoryHost`
capability methods added by T23.5 after the fixture's 2026-09-04 scan, which the fixture header's
"`defs` is complete" claim had silently stopped being true of. The six gained their def files,
annotated "(T8.19 repair)"; `str_field`'s `refs` emptied (T26.1 removed both call sites). The
resolver-gap suspicion in the task text does not hold: every one of the 68 missed ref sites was
grepped — all still exist except `str_field`'s two; the `Surface`/`PreToolDecision`/
`measurement_count` misses are the documented query limits (type positions, macro bodies,
path-qualified calls) already priced into the recall floor. The test now leads with
`graph_truth: precision 1.000 recall 0.510 over 28 labels / sites 76/149 / definitions 42/42 /
references 34/107 (0.318)` and prints each miss by name, so the next regression names numbers and
files; doc-comment counts updated to the measured values. On the "nobody noticed" half: `just
check` was *not* skipping the test — no `#[ignore]`, no feature gate — it ran red through ten
commits; that is a process fact, recorded here, CI untouched. Final full `just check` green.
Deviations: no resolver code changed — `src/plugins/graph/index.rs` is untouched; 2 files. The
landing round's own tasks staled two further entries (`plugin_json` — T15.11; `read_settings` —
T27.0); both removed with annotations in the fixture header, same rule.
