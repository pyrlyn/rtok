Source audit only; no product code changed. Reviewed Read search/tree path resolution, ignore and pattern rules, output boundaries and the Vfs mirror, plus log selection and rotation-follow state. Read the plugin instructions and compared existing Read/log contracts before filing reproducible gaps.

Execution plan:

1. Trace search/tree from tool arguments through `resolve`, root guard, `ignore::WalkBuilder`, `skip_git`, text-file loading, cap/archive recording and displayed paths. Compare hidden files, `.gitignore`/nested ignores, symlinks, invalid UTF-8, oversized files and roots outside cwd with the documented Read contract.
2. Trace regex compilation/fallback, max/depth normalization, ordering, snippets and empty/no-hit outputs in both production code and the Vfs mirror. Compare actual WalkBuilder behavior with tests and the stated plugin description; identify only cases where the two surfaces or contract diverge.
3. Trace logs filters and limits from CLI/config into model selection, JSON/export rendering, watch stream and logfile/store source. Compare ordering, inclusive time/level/source selection, zero/large limits, rotation and follow behavior without opening or exporting real host logs.
4. Deduplicate exact scenarios against T4.2–T4.6, T55.5, T56.2/T56.4/T56.5, T59.2, T83.16, T127/T263/T300/T383, T15.11/T24.3/T225.1 and existing plan/done/roadmap/ideas. Use small scratch fixtures to reproduce only uncovered candidates, then record sequential fix cards with source evidence, steps and checks.

Result: filed T589 for log generations being confused by repeated first lines or an empty replacement, and T590 for lexical parent normalization selecting a different file through a directory symlink. The two log fixtures share one card. No separate issue was filed for zero-result-limit clamping without a contrary contract, or the explicitly bounded search snippet. Also checked overlap with T430. This sampled pass does not establish exhaustive coverage of all Read filters, Vfs behavior or log exporters.

Evidence revision: product source matches `ee8d66288b1d1b850ba24b7f9a9a70481695c3ca` after merging main. An offline Rust harness compiled actual Follow/whole-line and resolve/normalize functions. A replacement with the same first line returned no rows despite an unread old tail and two new rows; an empty replacement followed by a write replayed both old rows. A Unix symlink/parent request read different contents through rtok than through native lookup; the direct-path control matched. The resolver used a standard filesystem adapter, equivalent Unix prefix checks and inactive dynamic roots. Full CLI/MCP integration and Windows were source-traced or left untested; no real host logs, application, provider or user data were used.

Validation: `CHECK_BASE=ee8d66288b1d1b850ba24b7f9a9a70481695c3ca mise exec -- just check` passed; the selective gate reported no code changes and skipped Cargo tests. `git diff --check` passed, and plan/todo contain 112 unique active IDs with equal sets.

Status: done 2026-10-11
Model: OpenAI / GPT-6
