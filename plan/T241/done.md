The golden and surface tests measure one call at a time; no test shows the saving over a whole session mix of Bash, Read, Grep and MCP results, so a change that helps one family and hurts the mix goes unnoticed.

Plan: `tests/fixtures/replay/session.jsonl` — about 30 anonymised hook payloads shaped like a real Claude Code session (tool mix taken from `rtok stats` on this machine, bodies written or scrubbed by hand; no real paths, names or secrets). `tests/replay_bench.rs` feeds them through `rtok hook` in a temp home, sums the `Measurement` rows, prints a per-plugin table (`--nocapture`) and asserts the total saving stays over a floor set a few points below the first run. Record the first run as a dated `research.md` §2 row with the command.

Check: the test fails when a plugin is disabled in the temp config; the `research.md` row cites the command; `just check` green. Needs T239.

Result: `tests/fixtures/replay/session.jsonl` (30 hand-written events: 23 Bash, 5 MCP `read`, 2 `search`, tool mix from `rtok stats --since 7d --json`) replays through the real hook/run/mcp surfaces in a temp home; `tests/replay_bench.rs` sums `Store::list_measurements`, prints a per-plugin table with `--nocapture` and asserts the total over a 76 % floor. `disabling_cmd_plugin_drops_the_total_below_the_floor` is the mutation check. Run 2026-09-25 on main: total 79.7 % (`cmd` 82.7 %, `read` 52.3 %); `research.md` row cites the command. Caveat carried in the row: `emit_filtered`'s trailer is not counted in `after_bytes` (bug noted in T239), so `cmd`'s share is an upper bound.

Model: Claude Code / opus-5.5 (first draft, abandoned unpushed in another session's worktree), claude-opus-5-5 (re-landed, reviewed)
