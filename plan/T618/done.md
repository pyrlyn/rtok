Confirmed on `2c7c40b2d8af0673f085d71d8d3e1da0051084e5` with the production [synthesize](src/plugins/memory/observe.rs#L153) and [strip_private](src/plugins/memory/scrub.rs#L48). A complete output `<private>SYNTHETIC_PRIVATE_MARKER` + 300 filler characters + `</private> public` is redacted by `strip_private` when passed whole. `synthesize` first truncates each input/output string to 200 characters, then the combined narrative to 400, and only then scrubs. That removes the closing tag; the paired-tag regex no longer matches, so the private marker remains in the narrative. The real Store persisted and returned that narrative in the isolated probe.

[capture](src/plugins/memory/observe.rs#L245) passes this synthesized narrative straight to `insert_observation`. Short complete blocks are covered by existing tests and redact correctly; the long complete-block case is not. T548 covers checkpoint prompts bypassing private-tag filtering entirely; this is a separate observation path where truncation defeats an existing scrubber.

Execution plan:

1. Add synthetic private-block regressions crossing the 200-character per-field and 400-character combined limits, for both tool input and response. Assert private text never reaches the stored narrative; retain short-block, ordinary-text and Unicode-cap controls.
2. Scrub complete field content before applying lossy truncation, using the shared privacy function. Ensure any bounded implementation tracks private spans across its cut rather than retaining an unmatched private prefix. Preserve the final narrative character cap.
3. Exercise capture through the memory host and Store retrieval/search so private payloads cannot reappear in recall. Cover other supported secret patterns crossing a cut, without real credentials in fixtures.
4. Run focused privacy, observation/capture and memory recall tests and `just check`.

Check: a complete private block remains private regardless of its position or length. The probe compiled the verbatim production synthesizer, imported the actual scrubber, and used real Store migrations/public methods; the full-block scrub, short-block and Unicode-cap controls passed. The full hook/capture integration was source-traced, not executed.

Result: `synthesize` in `src/plugins/memory/observe.rs` runs `scrub::strip_private` on each whole field before the 200-character cut; the ineffective final scrub after the 400-character cut is gone.

Check: `private_content_is_scrubbed_before_truncation_cuts_its_delimiters` (long private block, `sk-ant-` key across the cut) failed before and passes after. Hook cost on multi-megabyte outputs not measured.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
