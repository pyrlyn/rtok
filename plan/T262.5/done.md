Found 2026-09-24 while claiming T262.3/T262.4 (creator chose to fix Copilot first): `dispatch` stored the raw stdin in the call row, so a Copilot `preToolUse` (`toolName: view`, `toolArgs.path`) never matched the spawn-brief ledger's `tool_name` scan, and the `read` plugin's `PostToolUse` window read the same raw rows. Every adapted host (Copilot, Cursor, Gemini, Grok, CodeWhale, Cline, Devin) was affected.

Plan: when a host adapter ran, `dispatch_owned_strict` hands `dispatch` the adapted `HookInput` as JSON, so the stored row carries Claude's field names; Claude's own stdin stays byte-identical. Test: a Copilot `view` through `rtok hook PreToolUse --host copilot` reaches a later `SubagentStart` brief.

Check: `tests/hook_spawn_brief.rs::copilot_reads_reach_the_brief` fails on `origin/main` and passes here; `just check` green.

Do (Claude Code / claude-opus-5-5, 2026-09-24): as planned, in `src/hooks/mod.rs`. Unknown fields still round-trip through `HookInput::extra`. Unblocks T262.4.

Check result: fail-first shown by swapping in `origin/main`'s `src/hooks/mod.rs` (1 failed), then 5/5 pass with the fix.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
