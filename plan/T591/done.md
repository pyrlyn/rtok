Source audit only; no product code changed. Reviewed OTel export selection and watermark advancement, stream failure isolation, span/log mapping, metric sums and encoding against the module design and existing tests. This was a sampled audit; retention and all transport/platform behavior were not exhaustively verified.

Execution plan:

1. Trace bounded session/call/log selection, stream watermarks, retries and successful/failed export advancement. Compare late session completion, tied timestamps and in-flight calls with the delivery contract.
2. Inspect span/log mapping, metric aggregation and cumulative timestamps, including mutable calls and retention. Separate intentional compatibility limits from concrete data loss or incorrect output.
3. Reproduce uncovered cases with actual functions or store APIs in scratch fixtures; keep any adapters and unexecuted integrations explicit. Deduplicate against T16, T106, T143, T163.2, T207/T208, T229 and T395 plus all earlier audit cards.
4. File only confirmed findings with ordered fix steps and checks; move this audit to done, verify plan/todo parity and run the scoped documentation gate before updating PR #986.

Result: filed T592 for a late lower-ID session disappearing behind the recomputed same-second rank cursor. Compared completed T36.12 and its increasing-ID test: this records the uncovered ordering case rather than duplicating its old implementation task. The broad running-MCP hypothesis was dropped because native MCP records after execution. A hypothesized monotonic metric decrease from a mutable call outcome was not filed because no production outcome update was established. No additional metric or encoding defect was confirmed.

Evidence revision: `7db36a6d6e81f9f781cc67372d192f948ffe5d92`; main remained `ee8d66288b1d1b850ba24b7f9a9a70481695c3ca`. An offline scratch Rust executable linked the actual `rtok-store` crate and compiled `advance_sessions` from the exporter. With an in-memory database, exporting `z-first` and subsequently ending `a-late` in the same second returned `z-first` again, then no pending sessions. Reversing the lexical ID order correctly exported the late session. No SQL stubs, network, real collector or host data were used; full HTTP/CLI integration was source-traced, not run.

Validation: `CHECK_BASE=ee8d66288b1d1b850ba24b7f9a9a70481695c3ca mise exec -- just check` passed; the selective gate reported no code changes and skipped Cargo tests. `git diff --check` passed, and plan/todo contain 113 unique active IDs with equal sets.

Status: done 2026-10-11
Model: OpenAI / GPT-6
