Found 2026-09-22 by T166's first full `just check` (the T166 failure had been hiding the tail from every fail-fast run): `tests/report_pdf.rs::pdf_has_the_html_headings_in_order_and_the_charts` — the seeded PDF's body had no `Recommendations` heading at all, only its findings. Root cause in `src/report/pdf.rs::chunk`: the orphan-heading rule ("an orphan heading moves with the block it introduces") returned `Vec::new()` with the heading already consumed from the `Peekable` and dropped from `take`, so the heading vanished from the body (the contents page still listed it). The window is narrow — the heading fits a remainder of exactly its cost while the first block does not — so it only fires when content lengths land on it; the kimi merge's extra config keys widened the Config section into it.

Plan: `chunk` over a `VecDeque` so the orphan rule puts the heading back (`push_front`) and it moves to the next page with its block; a unit test pinning the consume-or-not behaviour at a 3-line remainder; `tests/report_pdf.rs` green again.

Check: `--test report_pdf` green (both tests) and the new unit test fails on the old `take`-dropping rule; `just test` green modulo the T166 windsurf case until T166 lands.

Do (Command Code / claude-fable-5, 2026-09-22): `chunk` now works over a `VecDeque<PageItem>` so the orphan-heading rule pushes the heading back (`push_front`) instead of returning `Vec::new()` with the heading already consumed from a `Peekable` and discarded from `take`. The rule's intent is unchanged — an orphan heading moves with the block it introduces, and a heading beside a full-page chart on an empty page still stops the give-back (the old endless-loop guard, `rest < LINES`, kept). New unit test `an_orphan_heading_moves_with_its_block_instead_of_vanishing` pins the consume-or-not behaviour at a 3-line remainder; it fails on the old rule (the heading is gone and the queue one item shorter).

Check result (2026-09-22): `--test report_pdf` 2/2 green — `pdf_has_the_html_headings_in_order_and_the_charts` restored (every heading is back in the body) — and `report::pdf` units 7/7 including the new pin; `fmt --check` and `clippy -D warnings` green; full `cargo nextest run` — 963 passed / 1 failed / 4 skipped, the one failure the known T166 windsurf case whose fix lands with T166.

Deviations: none. The trigger (extra config keys shifting the page remainder onto the window) is content-dependent, so the unit test pins the behaviour rather than the seeded layout.

Status: done 2026-09-22
Model: Command Code / claude-fable-5
