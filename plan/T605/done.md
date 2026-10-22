Source audit only; no product code changed. Reviewed `src/plugins/inject` candidate loading, aggregate budget selection, measurements, and SessionStart/UserPromptSubmit placement against T2.4, T7.1, T188, T189 and the plugin invariants.

Execution plan:

1. Trace candidate construction, token estimation, truncation/drop reporting, measurement writes and event dispatch.
2. Probe budget boundaries, Unicode mode-file reads, repeated output stability and mode placement with isolated fixtures and controls.
3. Deduplicate exact scenarios against the plan, done list, roadmap and ideas; record only confirmed gaps and distinguish source tracing from executed paths.

Result: filed T606 for Unicode mode text that fits the estimator budget but is truncated by a byte-count read cap before the shared budget fitter can see it. The existing T2.4 intentional 500+500 priority-selection overshoot was not filed separately. T188 introduced bounded mode reads and tested a 1 MB ASCII mode; T189 concerns PostToolUse archive references. Neither covers this Unicode byte/character mismatch.

Evidence revision: `912c0a8dd8561b74ce5531cdb67e5d6f7153ca72`; fetched main was `085b49e9450de9326e7738dc441718e1bd667363`. An isolated executable compiled the production `read_mode`, budget fitter, `apply` and token estimator functions with real scratch files and minimal config/Measurement adapters. The ≤250-token small-emoji input had 1,016 characters and 4,016 bytes; the reader retained 3,360 bytes, and final context was 3,361 bytes / 201 estimated tokens after the newline, losing its distinctive tail despite its 242-token estimate. Cyrillic within the shared 800-token budget showed the same loss; same-length ASCII, repeated-output, and missing-file builtin controls passed. Full hook dispatch, checkpoint composition and persistent Measurement storage were not executed.

Validation: `CHECK_BASE=085b49e9450de9326e7738dc441718e1bd667363 mise exec -- just check` reported no code changes against merged main and skipped Cargo tests for this documentation-only diff. The isolated probe passed its reproduction and control assertions; `git diff --check` passed, and active plan cards and `todo.md` contain the same 117 unique IDs.

Status: done 2026-10-11
Model: OpenAI / GPT-6
