Best competitor: rtk / sqz. Design: wc: drop padding, keep `total`; cargo: #1077 + group warnings by lint code `unused_imports ×12 (files…)`.

Check: #1076 harness (`bench/c14`) shows ≥ rtk on this category with full anchor retention.

Plan: wc formatter drops padding and keeps `total`; cargo clippy/build warnings (on top of #1077) group by lint code `lint ×N (files…)`, keeping every distinct path and all errors; fixture tests per formatter; never worse than raw.

Outcome: `wc` gets its own formatter (padding dropped, `total` kept; golden `wc`), and `cargo build`/`clippy` warnings are grouped by lint code (`unused_imports ×3: … (locations)`) keeping every distinct message, all errors and the exit line. Tests: two `compact.rs` unit tests plus the `wc` golden. Measured on the repo fixtures: the #1076 `bench/c14` harness is not on main.
