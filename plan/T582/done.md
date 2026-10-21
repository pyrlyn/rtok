Source audit only; no product code changed. Reviewed Batch client validation, submit/status/fetch, result recording, Flex application/retry policies, OpenAI Responses usage handling and the shared streaming tee. Compared behavior with the proxy/plugin instructions, `docs/batch-flex.md`, and existing Batch/proxy tasks before recording distinct findings.

Execution plan:

1. Trace Batch submit/status/fetch through proxy routing, result parsing and persistence. Compare repeated downloads and partial/error results while distinguishing client output-file handling from proxy result accounting.
2. Trace Flex request shaping, lane policy, retry/backoff, response parsing, service-tier persistence and usage/cost aggregation. Compare documented success, fallback and retry boundaries.
3. Trace OpenAI Responses JSON/SSE usage extraction and streaming tee behavior. Use offline scratch probes for repeated result recording and incomplete UTF-8 tails; source-trace transport/capture behavior without provider calls or host writes.
4. Deduplicate concrete scenarios against T385, T250–T255, T326, T11.3, T45.2, `docs/batch-flex.md` and plugin instructions. File only source-confirmed gaps with ordered implementation steps and checks; leave unproven platform or lifecycle assumptions unfiled.

Result: filed T583 for replayed Batch result downloads adding duplicate usage rows and T584 for an incomplete UTF-8 tail erasing earlier complete SSE usage/tier events. No additional Flex defect was established. Basic Batch validation had no distinct issue, and the Windows output-cleanup concern was not filed because it was not platform-proven. This pass does not claim exhaustive coverage of every proxy provider or transport state.

Evidence revision: `1713f7b34b172d24841ffb181f5d3060024dfdbb`. An offline scratch harness exercised actual Batch record/parse/usage functions with an append-only Store stub and actual wire/Anthropic extraction functions. Replaying one 100-input/20-output result under another call ID changed the aggregate from 120 to 240. A complete `message_start` with 100 input tokens and `standard` tier survived the full and ASCII-safe controls, but both usage and tier disappeared when a later UTF-8 character was split. The probe did not use a real store, provider, server, or 256 MiB allocation; transport cancellation and persistence details were source-traced. No host app or API key was used.

Validation: `CHECK_BASE=1d5889ab36b485befdda0355687a394bf8380077 mise exec -- just check` passed for the documentation-only diff; the selective gate found no code changes, so no Cargo tests were selected. `git diff --check` passed, source links were reviewed, and active plan/todo IDs matched uniquely.

Status: done 2026-10-11
Model: OpenAI / GPT-6
