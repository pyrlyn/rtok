---
status: in progress
priority: P3
complexity: 1
readiness: 50%
agent: Claude Code / claude-opus-5-5
---

# T369.1. Measure grep_symbol follow-up rate after an opt-in window

Turn `plugins.guard.grep_symbol` on for a dated window and measure the share of follow-up Grep/Read on the same name within 3 calls (`guard/grep_symbol` rows against the transcripts). At most 25 % means propose default-on to the creator; above it, keep the flag opt-in and record why. Also re-measure the PreToolUse hook p95 on an idle machine (`cargo test --release --test latency -- --test-threads=1`; the flag-on test is `latency_hook_grep_symbol_answer_p95_under_10ms`); on 2026-10-06 the load average was 46 and even the baseline run failed the 10 ms gate. Record the result as a dated row in `research.md` §29.5.

Check: a dated `research.md` §29.5 row with the follow-up share and the idle-machine p95 (under 10 ms); `just check`.

Progress (2026-10-21, Claude Code / claude-opus-5-5, `research.md` §29.5 T369.1 row): the metric is now an `rtok stats` line, `grep symbol answers N  tokens T  then same-name Grep/Read F (P%)` (`GrepSymbolRow`, `fold_grep_symbol` in `src/measure/stats.rs`). It finds answers by `grep_symbol::ANSWERED` and the name by the hook's own classifier. The follow-up share is not measured: no window has run (the flag was never on, and these transcripts hold no native `Grep`), and this session could not read `~/.rtok`, `~/.claude` or run `rtok stats` on them. Hook p95 was measured under load (9–11), not idle. The flag-on answer passed the 10 ms gate in one of three runs (p95 9.58–10.59 ms). Plain `PreToolUse` passed all three (9.09–9.46 ms). Left: the creator's window and `rtok stats --since <days>`, and a re-run of the latency suite on an idle machine (protocol in §29.5). The default stays `false` until then.
