Review the repository and available failure evidence for confirmed bug classes that do not already have active, roadmap, or completed coverage. Compare findings against existing cards before assigning sequential IDs; record confirmed fixes as ordered plan cards and unresolved, unapproved propositions in `ideas.md`. Keep the main checkout's dirty T522–T527 changes intact and work from this isolated checkout.

Execution plan: review subsystem boundaries and recent error evidence; verify each candidate against source and existing plan/roadmap/done/ideas entries; add distinct fix cards in severity order and reserve sequential IDs; sync the task table and `todo.md`; put uncertain candidates in `ideas.md`.

Check: audit cards are deduplicated, ordered and synced; `just check`.

Result: reviewed hook dispatch/resident handling, proxy shaping/routing/cache/wire parsing, archive retrieval/decisions, read rendering/dedup/search, command capture/fallback, MCP framing/expansion, memory save/revision/import, and store retention. Filed nine distinct source-confirmed defects as T531–T539, each with a concrete trigger, source locations, ordered implementation steps and acceptance checks. Added seven optional improvement proposals I-120–I-125 and I-129. No product fixes or dependency changes were made.

Evidence revision: `0b14b88eb56469625eebc7e1df92ae1b77bcf2b2`. An offline Rust harness compiled verbatim functions from this tree and confirmed four direct-cache normalization collisions plus the reverse-request MCP id collision. Other findings have explicit source-derived reproduction steps; their end-to-end regression tests belong to the fix cards. The installed rtok 0.15.1 was not used as proof for the 0.17.0 source. Neither codebase-memory nor CodeGraph tooling was available, and this repository has no `.codegraph` index; discovery used the available rtok explorer/read tools and targeted source reads. This audit is not an exhaustive proof of all host/platform behavior.

Validation: `CHECK_BASE=0b14b88eb56469625eebc7e1df92ae1b77bcf2b2 mise exec -- just check` passed for the actual documentation-only diff (no Cargo tests selected); `git diff --check` passed. The initial default-base run selected unrelated upstream changes and was stopped during the full gate, so it is not claimed as a completed test run. The active task table and `todo.md` match at 72 ids. Removed four stale todo entries (T372, T373, T413.1, T413.2) only after verifying their completed cards in this file.

Status: done
Model: OpenAI / GPT-6
