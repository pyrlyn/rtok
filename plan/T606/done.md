Confirmed with the actual [read_mode](src/plugins/inject/mod.rs#L119), [fit_budget](src/plugin.rs#L37), [apply](src/plugins/inject/mod.rs#L148) and [token estimator](src/tokens.rs#L22). `read_mode` computes `ceil(budget * prose_rate) + 1` and reads that many **bytes**, while the estimator divides the number of Unicode **characters** by the prose rate. At the defaults of 800 tokens and rate 4.2, a mode containing 1,000 four-byte characters followed by `\nKEEP_THIS_TAIL\n` is only 242 estimated tokens (1,016 characters), but exceeds the 3,361-byte read cap. The reader returns 3,360 bytes and loses the tail; the final injection has no `dropped:` marker. This example even fits the README's 250-token per-mode guidance. A 2,016-character Cyrillic mode likewise loses its tail despite fitting the shared 800-token budget; the same-length ASCII control remains intact.

The truncation happens before [modes_text](src/plugins/inject/mod.rs#L87) passes the mode to `fit_budget` and before aggregate injection accounting, so neither stage can recover the unread instructions. This is unnecessary truncation of content that fits the configured estimator budget, not a claim about actual model token billing. T188 introduced bounded mode reads to fix oversized input and tested a 1 MB ASCII mode; it does not test Unicode modes that already fit the token budget. T189 addresses PostToolUse archive references; neither covers this byte/character mismatch while loading mode files.

Execution plan:

1. Add file-backed regressions for two-, three- and four-byte UTF-8 text that fits the configured estimated-token budget but exceeds the current byte cap. Include a distinctive tail and assert that the entire fitting mode survives; retain ASCII and missing-file builtin controls.
2. Bound file I/O in a way consistent with the estimator's character budget. Account for UTF-8's maximum bytes per scalar or decode incrementally with a bounded character allowance, using checked arithmetic and preserving character boundaries. Keep large files bounded rather than reading them all into memory.
3. Run the decoded text through the shared budget fitter. Preserve fitting content and define how genuine truncation is reported/accounted for without changing T2.4's intentional candidate-selection policy. Check zero/small budgets, partial UTF-8 at the read boundary and multiple mode files.
4. Add SessionStart coverage with an actual Unicode mode file: assert the fitting tail survives, repeated unchanged runs remain byte-identical, and mode text is absent from UserPromptSubmit. Run focused injection/token tests and `just check`.

Check: the reproduced 242-token mode survives in full under budget 800; genuinely oversized files remain bounded and existing priority/budget behavior stays intact. The isolated executable compiled the production read, fit, apply and estimate functions, used real scratch files and minimal config/Measurement adapters, and observed stable repeated output. ASCII and missing-file builtin controls retained all fitting text. Full hook dispatch, checkpoint composition and persistent Measurement storage were source-traced or excluded, not executed.

Result: `read_mode` in `src/plugins/inject/mod.rs` caps by characters (`ceil(budget*rate)+1`, 4 bytes each), drops a partial UTF-8 tail instead of producing U+FFFD, and reads via `take().read_to_end` without pre-allocating the cap.

Check: `read_mode_keeps_a_fitting_multibyte_mode_whole`, `read_mode_bounds_a_huge_multibyte_file_on_a_character_boundary`, `session_start_keeps_the_tail_of_a_fitting_unicode_mode`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
