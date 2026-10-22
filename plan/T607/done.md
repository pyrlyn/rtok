Source audit only; no product code changed. Reviewed `src/modes/compress.rs` and `src/modes/ladder.rs`, comparing code/prose boundaries and ladder precedence with existing tests and the earlier T554 source audit.

Execution plan:

1. Trace fenced and inline code segmentation, phrase/article stripping, whitespace cleanup and ladder rule ordering.
2. Probe boundary cases with isolated inputs and controls, recording exactly which production functions run.
3. Deduplicate against plan, done, roadmap and ideas; file only reproduced contract violations.

Result: filed T608 for article-like suffixes removed from identifiers and paths, and T609 for shorter triple-backtick runs closing longer Markdown fences. The corrected probe passed 128 ladder combinations; no ladder precedence defect was found. T554's earlier broad compression pass found no additional issue, but its checks did not cover these exact suffix and mixed-fence cases. An initial control assertion also included whitespace outside the fence; it was corrected to isolate the fenced region rather than treating expected external whitespace cleanup as a defect.

Evidence revision: `09ed28336d360fde33398de3d2b8ba9c1dfa5eff`; fetched main was `085b49e9450de9326e7738dc441718e1bd667363`. The scratch executable imported the actual compression and ladder modules by path, with no adapters. It reproduced `foo_a` → `foo_`, `/tmp/a` → `/tmp/`, `item1a` → `item1`, and loss of prose-like content inside a four-backtick fence containing an inner triple-backtick run. Lite/name and ordinary triple-fence/double-backtick controls passed. This confirms native helper behavior; it does not establish production hook compression, since these helpers are used by tests and comparisons rather than wired into the hooks.

Validation: the isolated probe passed its reproduction and control assertions; `git diff --check` passed. The scoped `just check` against `085b49e9` reported no code changes and skipped Cargo tests for the docs-only diff. Active plan cards and `todo.md` contain the same 119 unique IDs.

Status: done 2026-10-11
Model: OpenAI / GPT-6
