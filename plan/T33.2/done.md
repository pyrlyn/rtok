**T33.2 implement optional L0/L1/L2 loading** · T33.1 · `src/plugins/archive/mod.rs`, `tests/fixtures/tier_context/`
Do: implement tiered loading behind the flag. Lossless `expand` still required where the source is not regenerable. Measure against v0.1 archive+inject on a documented fixture/session window.
Check: flag off → v0.1 byte-identical (`tiers_off_matches_v0_1`); flag on → L0 line-only cold blocks + L1 hot promotion (`tier_l0`/`tier_l1` kinds); fixture `tests/fixtures/tier_context/session.jsonl` (12 tool results); Gate P33 CTT baseline=126872 treatment=109908 ratio=86.6% (≤90% bar met); `expand` unchanged (L2 in store); inject untouched; `cargo test --lib plugins::archive::tests` 7 passed.
Status: done 2026-09-12
Model: Composer 2.5
Complexity: 4/5
