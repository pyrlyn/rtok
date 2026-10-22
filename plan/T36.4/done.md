**T36.4 rules trailer counts every line it dropped** · T36.3 · `src/plugins/cmd/rules.rs`
Do: the omitted counter only increments for lines the pick loop skipped, not for `take` lines dropped by `max` (nor for lines after the mid-loop `break`), so the trailer under-reports and the promised tail can disappear.
Check: a fixture whose `max_lines` cap drops `take` lines reports the true omitted count. `cargo test --lib cmd`, 22/22 pass, including `max_cap_reports_true_omitted_count` (10 lines / head3 / tail3 / max4 → 7 omitted).
Complexity: 2/5
Status: done 2026-09-11 · Model: Composer 2.5
