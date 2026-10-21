**T14.10 `toon` design** · T14.0 · `src/plugins/toon/PLAN.md`
Do: survey compact encodings for tabular tool results: TOON plus at least one outside source (CSV/JSONL, minified JSON, or a study of model accuracy on non-JSON encodings). Answer: on rtok's own captured tool-result corpus, at what array size and uniformity does the encoding actually win, and does answer accuracy hold when it does? Decide the detection rule for “tabular enough” and keep the plugin off by default until P9 says otherwise.
Check: shared Check; `Target:` is a bytes-saved threshold with an accuracy no-regression condition on the P9 set.
Status: done 2026-09-02 · `src/plugins/toon/PLAN.md`. Off until P9; tabular detection rule. Target: P9 cost/pass-rate.

Gate P14 (review) · Status: done 2026-09-03 · Check: 10 PLAN.md; every `Target:` is a verbatim `roadmap.md` gate sentence (`every_target_matches_a_roadmap_gate`); guard Mechanism is T2.6 Deny. §6: PLAN files batched in `830e049` with measure impl; cannot satisfy PLAN-before-code `git log` order.
