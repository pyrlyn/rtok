`research.md` §23: Copilot CLI's `subagentStart` prepends `additionalContext` to the subagent's prompt (not for the built-in general-purpose agent). Add it to `plugins/copilot/hooks/hooks.json` and the Copilot installer, and map Copilot's payload to rtok's `SubagentStart`. Unblocked by T262.5: call rows keep the host-adapted input, so Copilot reads reach `ledger()`.

Plan: Copilot's `subagentStart` stdin is `{sessionId, timestamp, cwd, transcriptPath, agentName, agentDisplayName?, agentDescription?}` and it reads a flat `{additionalContext}` (already what `copilot_output` writes). (1) `src/agents/copilot/mod.rs` `EVENTS` += `("subagentStart", "SubagentStart")`, regenerate `plugins/copilot/hooks/hooks.json`; (2) `hooks/types.rs`: `claude_event` maps `subagentStart`, `adapt_copilot` moves `agentName` → `agent_type` and `agentDescription` → `task_description`; (3) extend `tests/hook_spawn_brief.rs::copilot_reads_reach_the_brief` to spawn through `--host copilot` and read top-level `additionalContext`; bless install snapshots and docs that count Copilot events.

Result: the installer's `hooks/rtok.json` and the plugin tree's `hooks.json` both register `subagentStart` → `rtok hook SubagentStart --host copilot` (seven events). The dispatch path itself already worked — the event name comes from the argument and `copilot_output` already emits `additionalContext`; the gap was the missing registration. Both Copilot READMEs list the event.

Check: `copilot_reads_reach_the_brief` spawns through `--host copilot` with Copilot's own payload and finds the read path in top-level `additionalContext`; `hooks::types::tests::copilot_subagent_start_maps_agent_name_and_description`; `tests/copilot_plugin.rs` pins the plugin tree to the installer; the Copilot installer unit tests count seven events; `just check` green.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
