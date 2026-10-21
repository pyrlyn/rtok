Gate for I-91 (`research.md` §17.2). The Agent SDK hooks page says `updatedToolOutput` "works for any tool"; rtok's standing rule says PostToolUse can only add context. If the CLI honours it, native Read/Bash output could be shrunk in place (pointer + `expand <id>`) instead of wrapped or denied — that changes the design of `cmd`, `read` and `guard`, so it is a creator decision, not a silent change. No product code in this task.

Plan: throwaway hook script (scratch, not committed) returning `hookSpecificOutput.updatedToolOutput` for `Read` and `Bash` on the current Claude Code; run one Read and one Bash; check what the model received in the transcript. Repeat for an MCP tool.

Check: a dated row in `research.md` §3 with the Claude Code version, the payload sent and what the transcript shows, per tool kind. Honoured → the `AGENTS.md` rule line and I-91 are put to the creator with the row; not honoured → I-91 closes with the date.

Progress (2026-09-25, `research.md` §3 "T134"): Claude Code 2.1.267; the CLI hooks page lists no `updatedToolOutput` for `PostToolUse` (only `additionalContext`, `systemMessage`, `terminalSequence`), the Agent SDK page does. The live run is blocked in agent sessions (`claude -p` fails with an expired OAuth session, as in T53.1); left: the creator runs the scratch probe (hook logging the payload and returning `updatedToolOutput`, one Bash / Read / MCP call on `--model haiku`) from a real terminal, and the per-tool verdict goes into the row.

Do (Claude Code / claude-sonnet-5-5, 2026-10-15): the probe ran against LM Studio's local Anthropic endpoint (the hook runs in the CLI, so the model does not matter). `research.md` §3 "T134" holds the table: Bash, Read and MCP output are replaced when `updatedToolOutput` has the tool's own response shape (the `tool_response` the hook receives); a bare string is ignored for Bash and Read. I-91 stays open for the creator's decision on the standing "PostToolUse adds context only" rule; no product code changed.

Check result: dated row with version, payload and per-tool transcript in `research.md` §3; `ideas.md` I-91 gate updated.

Status: done 2026-10-15
Model: Claude Code / claude-sonnet-5-5
