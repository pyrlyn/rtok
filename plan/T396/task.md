---
status: todo
priority: P3
complexity: 2
readiness: 90%
agent:
---

# T396. Verify the usage readers against real files

From `research.md` §30 (T358.3, T358.4): the Kilo and Gemini CLI readers are "not run on real data"; Copilot CLI `inputTokens` vs its `tokenDetails` block disagree and are marked unverified; whether a resumed Copilot session writes a second shutdown with run-only or cumulative totals is unverified (the reader sums them); pi forked-session id copying is unverified. Grok's `signals.json` reader waits on the creator accepting observed keys as a source (§30.7) — ask when claiming.

Done means: each reader runs once on a real file from a machine with that host; the Copilot resume semantics are confirmed or the reader stops summing duplicate shutdowns; each `unverified` tag in §30 is resolved or kept with a date.

Check: a fixture per confirmed shape; §30 updated with dates; `just check`.

Execution plan (2026-10-12): 1. run the Kilo, Gemini CLI, Copilot CLI and pi readers read-only on this machine's own files and record the observed shapes (no content); 2. a synthetic fixture per confirmed shape; 3. check whether a resumed Copilot session writes cumulative or run-only shutdown totals and stop double counting if needed (test first); 4. date each §30 tag; Grok `signals.json` stays unverified until the creator decides.

Progress (2026-10-12, `research.md` §30): Copilot CLI 1.0.80 — `tokenDetails` is authoritative (it equals `usage` plus the compaction requests), the reader now takes it (`token_details_carry_the_compaction_requests_that_usage_leaves_out`, fail-first), and `inputTokens` including the cache legs is confirmed. pi 0.85.1 — reader sums match an independent sum over 953 entries; fixture for the `reasoning`/`cacheWrite1h` shape; fork id copying confirmed from source, not observed. Copilot resume: the reader keeps the last shutdown per session, cumulative per the SDK source (`github/copilot-sdk` 6be623b, `nodejs/src/generated/session-events.ts`; fail-first `a_resumed_session_counts_its_last_shutdown_not_the_sum_of_all`), not yet seen in a real resumed log. Left: a Kilo message row (this machine's db has none), Gemini CLI (not installed here), Grok `signals.json` (observed keys accepted by the creator 2026-10-13, but grok 1.0.34 wrote no `signals.json` in this machine's 3 sessions, `research.md` §30.7). Rechecked 2026-10-13: still 0 Kilo `message` rows and no Gemini chat files. Kilo done 2026-10-15 (`research.md` §30.1: a real `message` row read through LM Studio, fixture added). Still needs the creator to run one Gemini CLI (not installed here) and one Grok session (xAI-only, not runnable on a local model), then a reader and fixture per shape.

Rechecked 2026-10-21 on `62027a93` (`rtok agents usage --source logs`, read-only): `gemini` is still not on `PATH` and no Gemini CLI row is read; Copilot CLI still has 1 session (last day 2026-09-02), so still no resumed log; Grok is still skipped as `unsupported`, and this run could not list `~/.grok/sessions` to look for a new `signals.json` (sandbox). Kilo, pi, Kimi Code, OpenCode, Codex and Claude Code read without error. Nothing new to fixture; the three open items still need the creator's machine.
