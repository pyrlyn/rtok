Confirmed with the actual [text](crates/rtok-store/src/sanitize.rs#L70) and [body](crates/rtok-store/src/sanitize.rs#L34) functions: put ANSI reset `ESC [ 0 m` or U+200B between `system-` and `reminder` in an opening tag, followed by `hidden</system-reminder>\nvisible`. The first pass removes the noise but leaves `<system-reminder>hidden</system-reminder>\nvisible`; the second returns only `visible`. JSON string values show the same change on a second body pass. `text` removes wrappers before character cleanup, so cleanup can create a wrapper after the removal stage has finished. This violates T431's explicit idempotence requirement and the [property test's invariant](crates/rtok-store/src/sanitize.rs#L290). No existing card covers this composition case; T432's upstream cleaner intentionally retains wrappers and is a separate contract.

Execution plan:

1. Add deterministic text and JSON regressions for both reproduced tag/noise combinations, asserting one-pass normalization and `clean(clean(x)) == clean(x)`. Retain plain-wrapper, unchanged-byte and invalid-UTF-8 controls.
2. Order or combine character and wrapper processing so the result reaches the documented canonical form in one call. Account for transformations that expose new syntax; avoid an unbounded repeated-regex loop over large inputs.
3. Extend property inputs to deliberately combine noise inside wrapper names and adjacent/nested wrapper boundaries instead of relying on accidental random coverage. Preserve non-wrapper text and keep `terminal_noise`'s distinct whitespace/wrapper behavior.
4. Add storage-path coverage for ordinary and split hook bodies with `store_raw` on/off. Ensure persisted bytes, sizes and hashes refer to the final cleaned representation, then run sanitizer/store tests and `just check`.

Check: repeated stored-body sanitation no longer changes the reproduced inputs, raw storage stays byte-for-byte and the terminal-only cleaner keeps wrappers. The offline probe imported the actual sanitizer module without modification; no database, production replay, host record or upstream request was exercised. This is a P3 normalization-contract defect, not evidence of a privacy leak or observed production data loss.

Result: Stored-body cleaning reaches the canonical form in one pass, so removing noise that reveals a wrapper also removes the wrapper. The old wrapper regex stays only as a test oracle.

Check: `json_bodies_reach_the_canonical_form_in_one_pass`, rstest cases, proptest `wrappers_agree_with_the_fixed_point`, `a_wrapper_hidden_by_noise_is_removed_before_saving`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
