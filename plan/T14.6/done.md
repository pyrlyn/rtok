**T14.6 `inject` design** · T14.0 · `src/plugins/inject/PLAN.md`
Do: survey session-context injection: the four retired injectors plus at least one outside source (evidence on instruction dilution / lost-in-the-middle, or another agent's system-prompt budget). Answer: is byte-stability sufficient for cache safety across turns, and does more injected context measurably help or hurt at 800 tokens? Decide priority ordering and what happens to a dropped `Injection` (silent, or one line saying it was dropped).
Check: shared Check; `Target:` is the per-turn budget plus a byte-stability assertion.
Status: done 2026-09-02 · `src/plugins/inject/PLAN.md`. 800-token budget, byte-stable; dropped Injection is one line. Target: P2 inject contract.
