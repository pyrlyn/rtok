optimization.md §4.1. `rtok stats` shows prompt-cache hit rate per lane; a replay test proves the agent lane's request prefix stays byte-stable across turns with rtok's rewrites on.

Done: `Store::usage_by_lane` groups `usage` by `calls.kind` (Diesel join) and `lane::lane_of_kind` maps a kind back to its lane; `stats::attach_lanes` fills `Report.lanes` with the same `ApiRow` counters and hit rate as `api`. `rtok stats` prints a `lane` table and `--json` a `lanes` field once traffic ran off the agent lane; agent-only stores print byte-identical output. `tests/proxy_cache_replay.rs` replays a 9-turn growing conversation through the proxy against a mock upstream (compress mode, archive, toon, compress, tools_rewrite and the noise strip on, with context management off and armed) and asserts that tools, system, top-level fields and every message before the previous turn's live edge reach upstream byte for byte the same. `Sink` moved to `tests/common/proxy.rs`. docs/prompt-cache.md (en/ru/uk) describes both.

Check: per-lane hit rate from a fixture in `stats`; replay test green; `just check`.
Result: `stats_shows_the_cache_hit_rate_per_lane` and `stats_has_no_lane_table_for_agent_only_traffic` in `tests/stats_model.rs`; `agent_prefix_stays_byte_stable_*` (2) in `tests/proxy_cache_replay.rs`; `just check` green (2764 tests). PR #848.
Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
