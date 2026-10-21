Review previously unreviewed lifecycle and data-boundary paths in `src/tasks`, `src/worktree`, `crates/rtok-config`, `src/measure` and `src/plugins/graph`. Confirm each candidate in source and compare it with active, roadmap, completed and idea entries before filing sequential fix cards. Preserve the shared main checkout's dirty T522–T527 changes.

Execution plan:

1. Inspect task storage/parent completion, worktree lock and removal paths, configuration parsing, measurement ingestion, and graph project/index behavior.
2. Check candidate failures against existing cards and recent error evidence; assign reserved sequential IDs only to distinct source-confirmed defects.
3. Add ordered fix cards and `todo.md` rows; place broader unapproved proposals in `ideas.md`.

Check: every card has a source-backed trigger, implementation steps and acceptance criteria; plan and todo stay synchronized; `just check`.

Result: continued the bounded source audit across disk task identity/storage, task creation/status guards and remote pagination, worktree removal/cleanup/ownership paths, configuration layering and lenient loading, measurement transcript/cache analysis, and graph walk/watch/index/rank freshness. Filed six distinct defects as T541–T546, each with a source-backed trigger, ordered implementation steps and acceptance criteria. Existing T441.4, T35.5, T68.10, T35.4, T370 and T324 were checked for overlap; related feature cards are cross-referenced instead of duplicated. No additional sufficiently established worktree/config/measurement defect was filed in this pass. No product code, dependencies or ideas were changed.

Evidence revision: `efbb220bb5826dffeef36afd88910ac3d7424aa8` (product source still matches `0b14b88e`). An offline Rust probe compiled actual `walk.rs` and verbatim watcher event functions with the real ignore crate and minimal config/extension stubs. It reproduced unrelated gitignored files entering the full walk, explicitly included files being rejected by event filtering, and glob-excluded files being accepted by event filtering. T541–T543/T546 are source-confirmed with explicit reproduction steps; their full integration regressions remain work for the fix cards. No live watcher, real agent, remote task mutation or full application index run was used.

Validation: `CHECK_BASE=0b14b88eb56469625eebc7e1df92ae1b77bcf2b2 mise exec -- just check` and `git diff --check` passed for the documentation-only change; no Cargo tests were selected. Task table/todo identities and new source link targets were checked. This is further audit coverage, not a guarantee that every module or platform is bug-free.

Status: done 2026-10-11
Model: OpenAI / GPT-6
