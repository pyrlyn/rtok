Rule: a saving that is not a `Measurement` row does not exist, and the brief is a cost first. Needs T128 and T130.2.

Plan: T130.1's hook records a `Measurement` (`plugin: "memory"`, `kind: "brief"`) with the tokens it added (before = 0, after = brief) so the cost shows as negative saving; `rtok stats` `subagents` row splits the re-read share and sub-agent input tokens by "spawned with a brief" vs without.

Check: fixture with one briefed and one plain sub-agent asserts the split; after a dated window with the flag on, `research.md` §17 gets the measured net; default flips to on only if net tokens saved > 0 — otherwise the card closes with the number and T130 stays off.

Progress (2026-09-25): the cost row was already recorded by T130.1; `tests/hook_spawn_brief.rs` asserts one row per fired brief and none otherwise. `rtok stats` splits the `subagents` re-read share and input tokens into `brief` / `no brief` by `measure::subagents::SPAWN_BRIEF_MARKER` (a `handoff.rs` test pins it inside the brief's `INSTRUCTIONS`); fixture test `briefed_and_plain_subagents_split_the_reread_share`. A local LM Studio attempt (2026-10-15) measured only noise.

Result (2026-10-21): the dated window did not need a new paid run. T292 has had `spawn_brief` on since 2026-10-04, so this machine's transcripts already held one. Measuring it showed the split was blind: 0 of 881 sub-agents counted as briefed against 1,021 `memory`/`brief` cost rows. Claude Code 2.1.304 writes `SubagentStart` `additionalContext` as a `hook_additional_context` attachment record, not in a `user` message. `jsonl::Parsed::subagent_start_context` now collects that record, and `has_spawn_brief` checks it before the first user message. The fixture uses the real record shape, and the plain agent gets an id-only `SubagentStart` context. `jsonl::tests::subagent_start_context_comes_from_its_attachment_only` pins the parser. Measured net in `research.md` §17.5: 525 briefed vs 356 plain sub-agents. Re-read share was 6.8 % vs 4.5 % and the brief costs 198 tokens per spawn (≈ 104 K in the window), so net < 0. No default changed: D35 (creator) keeps `spawn_brief` on, and reversing it is the creator's call.

Status: done 2026-10-21
Model: Claude Code / claude-opus-5-5
