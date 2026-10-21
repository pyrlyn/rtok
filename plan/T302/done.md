Found by a bug-hunt pass over `src/report/**`. Two verified correctness bugs, both in code that formats measured data for a human or a downstream model to read:

1. `report::ai::expand()` formatted `100.0 * exp.rate` with a raw `{:.1}` instead of routing it through `markdown::dec()` like `markdown.rs`/`html.rs` already do for the same value. A non-finite `rate` (a corrupted/legacy DB row — exactly the case `report::fixtures::hostile()` exists to cover for the other two renderers) printed the literal string `rate=NaN%` or `rate=inf%` into the `--ai` report, which is read by a model/parser downstream; the other two renderers correctly print `—` for the same input (T105).
2. `html.rs` and `pdf.rs` each cast a `u64` call/bust count to `i64` with a plain `as i64` when building chart data (`r.calls as i64`, `*n as i64`). For a count exceeding `i64::MAX` this bit-reinterprets instead of saturating, producing `-1` in the chart while the adjacent table (fed from the same `u64`) correctly showed the huge count — a self-contradictory report, reproduced verbatim in the `html::tests::one_row_snapshot` fixture before the fix.

Also tightened, same module, same theme (unchecked arithmetic on store-derived `i64` values in `advice.rs`): `retire_plugin`'s `-r.saved` and `inject_budget`'s `budget * r.rows as i64` now use `saturating_neg`/`saturating_mul` instead of an operator that panics in debug and wraps in release on an extreme stored value; and `top_sinks` now ranks by an estimated token count (`before_bytes` divided by `[estimator] code`) instead of a raw byte count, so its sort key is denominated the same way as every other rule feeding `recommendations()`'s single `Reverse(tokens)` sort (the module's own doc comment: ordered "by the tokens it would recover").

Fix: `ai::expand()` now calls `super::markdown::dec(Some(100.0 * exp.rate))`; `html.rs`/`pdf.rs` use `i64::try_from(..).unwrap_or(i64::MAX)` for both chart-data casts.

Check: new `report::ai::tests::non_finite_rate_never_leaks_into_the_document` (NaN and infinity, mirrors markdown.rs/html.rs's T105 `holds()`); the html `one_row_snapshot` fixture re-blessed to show the correct huge count instead of `-1` in the chart; new `report::advice::tests::top_sinks_ranks_by_estimated_tokens_not_raw_bytes`. `cargo test --lib report::`, `cargo fmt`, `cargo clippy --lib -- -D warnings` all green.

Status: done 2026-09-28
Model: Claude Code / sonnet-5
