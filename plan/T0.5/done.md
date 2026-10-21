**T0.5 token estimator** · T0.2 · `src/tokens.rs`
Do: `estimate(text, class) -> u32` with classes `Code`, `Prose`, `Json`, `Cjk`; defaults 3.5 / 4.2 / 3.0 / 1.0 chars per token, loaded from config; `tokens_saved(before, after)`. Document ±15 % error in a doc comment.
Check: unit tests on 3 fixtures; `estimate("", _) == 0`.
Status: done 2026-09-01 · 3 tests; rates come from `config::Estimator`; counts chars, not bytes (CJK fixture).
