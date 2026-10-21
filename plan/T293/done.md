SessionStart recall is already on and already title-only (`recall_tokens` 200, up to 5 lines). On this machine `rtok memory status` showed 125 live notes, 235 recalls, and `mem_get` called 0 times (2026-09-27), so the titles never become a memory the model can use. claude-mem's index works because each line shows the retrieval cost and names the tool that fetches the body (https://docs.claude-mem.ai/progressive-disclosure, fetched 2026-09-27). The diagram token counts on that page are the vendor's illustrations.

Plan:
1. Worktree `_worktrees/rtok-T293`. Independent of T291; land before or with T292 so the newly-on paths use the same line shape.
2. `recall` and `prompt_recall` stay inside their budgets and still contain no bodies. Each title line gains an estimated body-token count. One line says these are titles, and names `mem_search` for the turn and `mem_get` for a matching id.
3. Zero notes for the resolved project inject one line naming that project key, inside the 200-token cap.
4. `mem_get` writes a `Measurement` (bytes and estimated tokens) and returns the verbatim body, retired prefix included (D4). The tool description points at the index. No truncation, no new tool, no embedding call.

Check: a fixture with three notes asserts the injected text has ids, titles and token counts and does not contain a body secret; an empty project asserts the one-line key; `mem_get` records one `memory` measurement and returns the full body; `just check`.

**Result (2026-10-04).** The recall index carries a body token estimate and names `mem_get` (`INDEX_GUIDE` in `src/plugins/memory/mod.rs`); `mem_get` records one `mem_get` measurement row and returns the full body (tests in the same file).
