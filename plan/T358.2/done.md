Scope: the T358.2 bullet under "Split when claiming" in T358 (`--source logs` for Claude Code and Codex on the existing transcript readers, and `--source both`), plus the `source` default flip to `logs` and display names. Moved to T358.6 to stay near 300 LOC: `--by agent|model`, the saved columns and `rtok saved` line, the JSON `skipped` field, and agents that exist only in the store when `both` is used. `[agents.usage.dirs]` moves to T358.3: the two hosts here read the existing `[stats] transcripts_dir` and `codex_dir`.

Check: the T358 Check items for `logs` and `both` on fixture homes for Claude Code and Codex; `just check`.

Execution:

1. Stack on T358.1 (#656). `jsonl::Usage` gains `ts` and `model` (the parser already dedups streamed messages by `message.id`); `codex::requests` returns one record per `token_count` line and `codex::collect` sums it, so there is one Codex parser.
2. `measure::usage` turns both into `UsageSlice` rows (sub-agent transcripts join the parent's session); `agents::usage::report` takes its rows from the logs, the store, or both and fills `through_rtok_tokens` and `coverage` per agent for `both`.
3. Fixture logs under `tests/trycmd/input/usage-logs/`, unit tests, trycmd goldens, `just check`.

Result (2026-10-03, Claude Code / sonnet-5): `rtok agents usage` now defaults to `--source logs`: the Claude Code transcripts (`[stats] transcripts_dir`) and Codex rollouts (`[stats] codex_dir`) are read on the fly, never written to the store, and bucketed per request by day and month in `--tz`, priced through `[stats.prices]` like the `rtok` source. Agents print with their display names (`Claude Code`, `Codex`; JSON carries `host` and `name`). `--source both` keeps the logs' totals and costs and adds `Through rtok` and `Coverage` columns (JSON `through_rtok_tokens`, `coverage`), so an agent that bypasses the proxy reads low. Reused, not rewritten: `jsonl::parse_path` (message-id dedup), `codex::jsonl_paths`, `subagents::is_subagent`, `stats::row_cost` and `parse_since`. Known limits: a Claude Code session resumed into a second file is counted in both (dedup is per file, as in `rtok stats`); a request with no parsable timestamp is left out. Checked: unit tests for both readers (streamed duplicate, `<synthetic>` zero turn, sub-agent session, model from the latest `turn_context`, unparsable timestamp), logs totals with one unpriced model across the UTC and Kyiv month edge, `both` coverage, trycmd cases `agents-usage-logs` and `agents-usage-both` on fixture files, `config_coverage` and `surface_parity`.

Status: done 2026-10-03
Model: Claude Code / claude-sonnet-5
