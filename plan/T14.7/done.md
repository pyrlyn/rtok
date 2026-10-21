**T14.7 `guard` design** · T14.0 · `src/plugins/guard/PLAN.md`
Do: survey duplicate/loop suppression: token-optimizer's refetch guard plus at least one outside source (an agent framework's loop detection or repetition penalty). Answer: is denying the right move at all, or is rewriting the call into `expand <id>` strictly better (the model gets its answer, we still save)? Decide the false-deny budget and the window semantics.
Check: shared Check; `Target:` is a max false-deny rate with the deny rate visible in `stats --plugin guard`.
Status: done 2026-09-02 · `src/plugins/guard/PLAN.md`. Rewrite to expand <id>, deny only if no archive. Target: false-deny < 1 % (guard gate).
