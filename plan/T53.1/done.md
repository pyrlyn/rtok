From I-18. Short nudges ("do not re-read", "use expand") may cut waste, but they are re-read every turn and dilute instructions.
Done when an opt-in `inject` nudge set exists as data (D7), stays inside the D5 budget and byte-stable, and a P7-style A/B on the bench shows it does not raise cost per passed task; without that result it stays off.

**Result (2026-09-18).** `modes/nudges.md` is D7 data (re-read / expand / outline-first / search-before-Grep), wired as opt-in `builtin("nudges")` in inject (default `modes = []`). Est. **114** prose tokens (cap 250). SessionStart `additionalContext` **0 B off / 478 B on**, byte-stable, absent from UserPromptSubmit. Dry `rtok bench` without `RTOK_BENCH_LIVE`: off and on both **6/6** pass, cost **0** (`live: false`). Live A/B attempted 2026-09-18 after creator spend approval: `claude` 2.1.236 present, `claude auth status` `loggedIn: false`, OAuth expired and `ANTHROPIC_API_KEY` unset (gateway key 401). No live tokens or `stats --price` rows; gate stays **do not enable**. Default `modes` left off.

---
