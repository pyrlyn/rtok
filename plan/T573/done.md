Confirmed with actual coalesce/fold/sweep functions: [coalesce](src/web/live.rs#L134) removes the oldest events beyond the display cap, including End events for calls already present in running. [CallsStore::fold](src/web/calls_store.rs#L213) clears running entries only from the retained events. Deliver Start A and Start B, then successful End A and End B in one batch with cap=1: the summary counts two completed calls, but A remains running because its End was discarded. At 120001 ms [sweep](src/web/calls_store.rs#L282) falsely marks A interrupted. The reader advances past the dropped End, so the next poll cannot repair it. Web and [TUI](src/tui/live_calls.rs#L118) share this state path. T329.15/T329.26 intentionally allow capped display rows and sampled tool/backend/latency bars; they do not permit a completed call to become a false interruption.

Execution plan:

1. Add the two-start/two-end fixture and an uncapped control; assert every completed call leaves running even when its End is not a displayed row.
2. Preserve terminal call identities independently of the capped display list, or apply lifecycle transitions before truncation. Keep bounded processing and the existing intentional display/statistics sampling contract.
3. Cover multiple omitted ends, ends without visible starts, interleaved progress, genuinely unfinished calls, late ends after a real timeout, and web/TUI consumption of the shared batch.

Check: successful omitted ends never create stale running or interrupted rows, pending calls remain visible, summaries and display caps remain correct, and `just check` passes. The isolated actual-source probe reproduced the false interruption and showed the uncapped control clearing both calls.

Result: `CallBatch` carries every ended call id, the fold clears running calls by it, and the display cap cuts ended rows before unfinished calls, so a completed call cut by the cap is never marked interrupted.

Check: `an_end_the_display_cap_cut_still_clears_its_call_and_never_becomes_interrupted`, `a_call_with_no_end_stays_visible_when_ended_rows_crowd_the_cap`, `a_capped_end_after_a_real_timeout_replaces_the_interrupted_mark`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
