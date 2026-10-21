**T22.4 `--ai`** · T22.1 · `src/report/ai.rs` (new), `tests/report_ai.rs` (new), `src/cli.rs`, `src/config/mod.rs`, `config/default.toml`, `docs/config.md`
Do: the same document shaped for a model instead of a person. No images, no styling, no box
drawing. Tables in `toon` rather than Markdown pipes — dense, and it dogfoods the plugin this repo
ships. Stable heading ids so a model can be pointed at one section. Every number keeps its unit and
its row count, because a model has no other way to weigh it. One budget (`[report] budget_tokens`),
and when the document does not fit, it says which sections it dropped instead of truncating
mid-table. Ends with the recommendations as an ordered, explicit task list.
Check: `--ai` output is measurably smaller in tokens than `--format md` on the same store and
window (a `Measurement` row, not an assertion in prose); no section is silently missing — the
dropped ones are named; every heading id is stable across two runs over the same data.
Complexity: 3/5
Status: done 2026-09-09 · Model: Muse Spark (meta/muse-spark) subagent
Check result: green. `src/report/ai.rs` (new) renders the same eight sections in the P22 order —
one `#id` anchor per section (`window` … `recommendations`, stable by construction), dense plain
lines, tables through the toon encoder (`tabular_keys`/`encode` are `pub(crate)` now, so the
`--ai` rendering dogfoods the plugin T22.2's entry pointed at instead of growing a second table
syntax; latencies reuse `markdown::ms`, also `pub(crate)` now). Without the `toon` feature the
same tables fall back to `k=v` lines (`build-min` stays silent). Every section states its `units:`
line and every number keeps its unit and row count (`total_saved=210tok rows=2`). The budget is
first-fit whole sections under `[report] budget_tokens` (default 8000, ~6× the fixture's 1316-tok
`--ai` output, so it binds only on very large stores); the past-budget sections are named in
`dropped:`, never truncated mid-table. `--ai` (flag and key, D12/D14) selects this renderer
instead of `--format`, through T22.2's committed `emit()` sink. CLI and tests: `tests/report_ai.rs`
4 passed — md 1630 tok vs ai 1316 tok on the same fixture store and window (−19%, −314 tok, the
measured proof), the eight `#id`s in order with md's numbers (`total_saved=210tok`, `hooks 2`,
`dropped: none`), a `budget_tokens = 100` config file naming its drops with no dropped heading
left behind, and an empty store saying `no rows.` / `no recommendations.`. Four unit tests beside
the renderer pin the task-list shape (`1. [rule] finding (evidence)`, nothing after it), block
wholeness under a tight budget, byte-equality across two runs, and toon-vs-pipes. Verified in a
detached worktree at `1246857` with only this task's files (the shared checkout carries
T15.6/T22.5/T25.x in flight): `cargo fmt --check` clean, `cargo clippy --workspace --all-targets
--all-features -D warnings` clean, `cargo test --workspace` green (30 suites, 0 failed),
`cargo build --no-default-features --features measure` silent, `just dup` exit 0 (38 clones, none
from the new files). One transient on the way: `report_ai` failed 4/4 once under a full-workspace
run (all in 0.04s at the binary-status assert — the shared `CARGO_TARGET_DIR` was being rebuilt by
sibling builds), green on immediate rerun and on the recorded full run; environment flake, not code.
Deviations: ~750 added lines across 7 tracked files + 2 new (brief: ≤200) — D12 forces the config
pair plus the docs mirror, the Check forces three measured proofs plus the task-list shape (8
tests), and the toon dogfood needs the 2-word `pub(crate)` share; precedent T22.1 (+880) and T22.2
(+370, whose entry already named this task's share as the home for its `ms()` mirror — that mirror
stays theirs to retire). No new dependency. Landing note: verified both before (`a054922`) and
after (`1246857`) T22.2's landing; the dispatch rides T22.2's committed `emit()`.
