---
status: todo
priority: P1
complexity: 3
readiness: 50%
agent:
---

# T385.3. Defaults bench: `compress` mode, context editing, skills, `live_blobs`

optimization.md §5. `rtok bench` cost per passed task for each setting on and off, recorded with a date in `research.md` (`tools_rewrite` is T124). Settings whose row shows a net saving with the pass rate held become default-on in a follow-up; the rest stay off with their number. Branch `t128-proxy-compress-default` (PR #562, `df2a13ba`) is prior art. Needs the creator's API spend for live arms (see T394).

Live-arm prep (2026-10-21, `research.md` §47): `bench/configs/defaults/` holds the three arms §16.10 keeps (`a` passthrough :8801, `b` compress :8802, `context` :8803, one `rtok proxy` and `RTOK_HOME` each), pinned by tests, with a step-by-step protocol for the creator. Nothing ran. `rtok bench` checks once per task per arm, never resets the working directory and averages failed runs in as zeros (I-140); the protocol works around that with `--runs 1` and a resetting `check`.

Check: one dated `research.md` row per setting.

Progress (2026-10-21, `research.md` §16.10): the offline half is in. A read-only pass over 30 days of this machine's Claude Code transcripts (204,565 requests) puts a ceiling on each setting's request side: `compress` mode (pointer only) 6.35 % of input tokens, 5.17 % of input cost net of cache re-writes; `skills` 0.11 % / 0.04 %; `live_blobs` 0 eligible blocks; `context_management` 22.95 % / 20.33 %, a clearing whose quality cost only a pass rate shows. No pass rate, so no default changes. Left: the live arms for `compress` mode and `context_management` (`skills` and `live_blobs` have too little to act on to be worth paid arms), which need long tasks with real checks in place of `bench/tasks.toml`, one `rtok proxy` per arm, and the creator's API spend (T394).
