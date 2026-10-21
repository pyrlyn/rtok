optimization.md §5 (I-85, I-86). Per-wire handling for deferred tool schemas and replayed thinking blocks, only where the measurement in each idea shows a saving (I-86 was rejected at 0.03 % — re-measure before building).

Check: per-wire tests and a dated bench row; `just check`.

Result: dropped, nothing built. Measured 2026-10-11 on 1,722 Claude Code transcripts (window 2026-09-11 to 2026-10-11, 153,539 requests, 22.92 G input tokens; `research.md` §37). I-85: 1,491 of 1,495 sessions (99.7 %) already defer MCP tools natively (404 `ToolSearch` calls against 4,631 MCP calls), and Anthropic and OpenAI both offer `defer_loading` server side; other hosts have no request bodies here, so no saving is claimed. I-86: the 0.0297 % of T125 was a measurement error (it counted the empty `thinking` text of omitted blocks, once per block). Replayed thinking is 11.5 % of input tokens (10.8 % to 12.4 %), 3.4 % of it from earlier turns, but dropping those nets only 1.1 % to 1.6 % of input cost after the cache re-creation each new turn causes (1.2 % of input plus output), against an unmeasured quality risk; a client-side edit is unsafe on accounts from 2026-08-31 (prefix check, 338 `thinking_drop` events in the window). If it is ever revisited, the only safe route is the server-side beta `clear_thinking_20251015` edit. `ideas.md` I-85 and I-86 are Rejected; the archive prefix-mismatch side finding is I-119.
Status: done 2026-10-11

Model: Claude Code / claude-sonnet-5-5
