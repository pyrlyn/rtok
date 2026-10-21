Source audit only; no product code changed. Reviewed config CLI dispatch, set/init validation, comment revival, TOML mutation and persistence against the typed configuration and existing coverage. Layered environment behavior and every configuration field were not exhaustively tested.

Execution plan:

1. Trace CLI config commands through TOML mutation, validation, layered loading and atomic persistence; read the crate's existing tests and relevant completed tasks.
2. Check structured keys/values and comment handling with valid TOML fixtures, comparing the requested edit with unrelated values and loader acceptance.
3. Reproduce uncovered cases with actual config APIs in scratch files and deduplicate against T12, T328, T362–365, T381 and previous audit findings.
4. Record only confirmed defects with ordered fix plans; move this audit to done, check plan/todo parity and scoped `just check`, then update PR #986.

Result: filed T594 for structured values accepted and persisted despite failing typed Config extraction, and T595 for commented-default revival mutating unrelated multiline TOML string content. The open-map scalar and invalid array element cases share T594. Existing range/env validation and comment-padding tasks do not cover these exact failures; no other candidate was filed.

Evidence revision: `286d8c85bf6deb1ad29b8d30f7697bd2a2f190af`; main remained `ee8d66288b1d1b850ba24b7f9a9a70481695c3ca`. An offline scratch executable linked the actual `rtok-config` crate. The setter accepted `stats.prices = 42` and `plugins.read.allow_paths = [1]`, and `issues` reported neither; pure Figment default-plus-file extraction into Config rejected both. Setting port 9000 also changed `# port = 12` inside an unrelated multiline literal string to `port = 12`. The ordinary commented-default control succeeded and dry-run preserved file bytes. The first probe generator had a Python quoting error before any fixture/build ran; its delimiter was repaired and the full probe passed. No user config, host application or network was used. Full CLI/environment integration was source-traced, not executed.

Validation: `CHECK_BASE=ee8d66288b1d1b850ba24b7f9a9a70481695c3ca mise exec -- just check` passed; the selective gate reported no code changes and skipped Cargo tests. `git diff --check` passed, and plan/todo contain 115 unique active IDs with equal sets.

Status: done 2026-10-11
Model: OpenAI / GPT-6
