Depends on T287. Pull-only messages wait until the agent thinks to call `agent_inbox`. Hosts with hooks (`research.md` §26: Claude, Cursor, Codex, Copilot CLI, Grok, Gemini, Kimi, ZCode, CodeWhale) can receive them at the next turn.

Plan:
1. Worktree `_worktrees/rtok-T288`.
2. `UserPromptSubmit` and `PostToolUse` add undelivered messages to `additionalContext` (PostToolUse adds context only), framed as in T287, at most `[agents] push_bytes` (default 1 KiB) per event; the rest as `… and N more: call agent_inbox`. Mark them delivered (not read).
3. One indexed query per event; measure against the 10 ms hook budget as in T282; nothing is printed when the inbox is empty.
4. Hosts without hooks: documented as pull-only; the SessionStart line from T283 mentions `agent_inbox` there.

Check: hook fixture tests (one message, over-budget batch, empty inbox prints nothing, delivered once); hook bench row; `just check`.

Execution (2026-09-27): one PR, cut on top of T287 PR 1. The push goes through the budgeted injection path on `UserPromptSubmit` and `PostToolUse` using the T287 frame. New key `[agents] push_bytes`. Messages are marked delivered, not read. The `agent_inbox` mention is deferred until T287 PR 2 ships the tool. Includes hook fixture tests and a hook bench row in the PR.
Progress (2026-09-28): PR 1 on branch `t288-push-messages`, stacked on #472; its PR opens when #472 merges. The hook latency bench must be rerun on a quiet machine before the PR claims its row.

Execution, step 4 (2026-10-03, Claude Code / sonnet-5): after T287 PR 2 shipped `agent_inbox`. The SessionStart/SubagentStart line says `agent_inbox` reads the messages sent to the agent, and the push's last line reads `… and N more: call agent_inbox (or run rtok agents inbox)`, so an MCP-only agent and a Bash-only agent both know the pull path. Tests: the existing hook unit tests and the `hook.toml` trycmd carry the new wording.

Result (2026-10-03, Claude Code / sonnet-5): the push itself landed in #488; step 4 is this change. The user-facing statement that hosts without hooks are pull-only goes into the T290 docs task, which owns the agents and worktrees docs.
