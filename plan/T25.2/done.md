**T25.2 `rtok agent sessions`** · T25.1 · `src/cli.rs`, `src/render.rs`, `tests/agents.rs` (new)
Do: render the model's page as a table — agent, provider, model, in / out / cache, started, and how
long it has run, newest first; `--all` includes sessions that have ended. Durations and the table
layout come from one helper in `render.rs`, because `demon status`, `stats` and this all pad columns
by hand today and the next one would be the fourth copy.
Check: two live sessions and one ended print two rows, three with `--all`; the token columns equal
`rtok stats` over the same window; an empty store prints a header and a line saying nothing is
running.
Complexity: 2/5
Status: done 2026-09-09 · Model: GLM-5.3 (subagent; policy tier GLM-5.3-Flash, effort Low)
Check result: green. `render::table(cols, rows)` pads every column to its widest cell with a
per-column width floor — the floor is what lets a fixed-width table move over byte-identically;
stats' api table, tool/bash/mcp sections and `stats --cache` were refactored onto it, byte-identity
proven by the unmodified full-equality goldens in `tests/stats_model.rs`. `demon status`
deliberately NOT refactored: it pads its state word before colouring so ANSI bytes stay out of the
width math, and its last column is an unpadded path — a measure-then-pad helper would change its
tty output. Also `render::duration()` ("45s"…"3d04h", two units max) and `sessions_table()`
(both cache counts labelled). Default window `since = 0`: the default view's selectivity is
liveness (`ended_at IS NULL`) — a started_at floor could hide a long-running live session, the row
the command exists for. `tests/agents.rs` 3/3: two live + one ended → 2 rows, 3 with `--all`;
`agents` alias byte-equal; token columns == `rtok stats --json` over the same window (fixture
transcripts mirror the usage rows turn-for-turn so both definitions agree); empty store → header +
"nothing is running". `just check` exit 0.
Deviations: the `agents` visible alias did not exist on the tree despite the P25 preamble — added
as one `#[command(visible_alias)]` line. `tests/config_coverage.rs` gained `"all"` in its
action-flag allow-list (`--all` is a view toggle, not a stored setting). At integration the
command joined T15.12's `COMMAND_PAGES` (`agent sessions` → `sessions`) — the page rides the
snapshot, so it is a real page, not an on-demand call.
