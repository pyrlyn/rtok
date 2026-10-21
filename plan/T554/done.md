Review source paths not yet covered in `archive`, `compress`, `json_tree`, `toon` and `guard`. Read each plugin's local `AGENTS.md` and `PLAN.md` first (the compression module has no `AGENTS.md`); compare concrete candidates with active, roadmap, done and idea entries before filing. Preserve the shared main checkout's dirty T522–T527 changes. This is a source audit and docs-only task.

Execution plan:

1. Trace archive identity/decision persistence and expansion, compression eligibility and byte preservation, JSON folding and TOON round trips, and guard match/deny paths against each module's invariants.
2. Use focused source probes or fixtures for candidate edge cases; avoid real agents and do not modify product source.
3. Deduplicate findings against existing cards and error evidence; add only distinct, source-confirmed fixes with implementation steps and checks, syncing `todo.md`.

Check: each filed finding has a source-backed trigger and concrete reproduction; no duplicate is added; plan/todo stay synchronized; `git diff --check` and `just check` pass.

Result: inspected archive block/blob/skill decisions and the pi carrier, extractive compression, JSON tree folding/rendering, TOON encoding and its test oracle, and guard read/Bash keys, mutation invalidation and skill resolution/frontmatter exemptions. Filed T555–T559 for five guard behavior defects and T560 for the test-only TOON decoder. Compared the exact triggers with T55.8/T55.9/T57.1/T322/T445/T62.1/T468 and active/roadmap/idea entries. No additional distinct archive/compress/json_tree failure was established in this pass. No product source, dependencies or ideas were changed.

Evidence revision: `eb13dd7f017e96547c9498555ea9e080814cad21` (product source unchanged from `0b14b88e`). An offline Rust harness compiled actual guard key/classification helpers, skill selection/frontmatter functions, and TOON encoder/test decoder. It reproduced quoted-path and Read-cwd key collisions, three writer variants classified as cacheable, selection of a sole foreign-project skill, failure to recognize valid YAML-quoted host keys, and the quoted-comma round-trip oracle failure. The repository's serde-saphyr version parsed the quoted YAML keys successfully. A separate system sed fixture created its output file in scratch; find/git mutations, real agents and live hook integration were not run. Full guard integration tests belong to the fix cards. The harness-created manifest fixture was removed after exact-content verification.

Validation: `CHECK_BASE=0b14b88eb56469625eebc7e1df92ae1b77bcf2b2 mise exec -- just check` passed for the documentation-only diff (no Cargo tests selected); `git diff --check` passed. Source links and active task/todo identities were verified. An additional docs-check was stopped during compilation and is not claimed as a completed test; its worktree build cache was cleaned.

Status: done 2026-10-11
Model: OpenAI / GPT-6
