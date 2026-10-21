**T14.2 `cmd` design** · T14.0 · `src/plugins/cmd/PLAN.md`
Do: survey command-output reduction: the three retired filters plus at least one outside source (structured runner output — `cargo --message-format=json`, `pytest -q`, `jest --reporters` — or a log-compression/dedupe algorithm). Answer: does a per-family formatter beat one generic head/tail/dedupe rule, and at what output size does the crossover sit? Decide the error-first rule: what must never be dropped when a command fails.
Check: shared Check; `Target:` is a % cut on a named command corpus at a stated expand rate.
Status: done 2026-09-02 · `src/plugins/cmd/PLAN.md`. Per-family formatters; never drop stderr on failure. Target: ≥40 % byte cut, expand < 5 % (P3).
