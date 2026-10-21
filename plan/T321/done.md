Found by a bug-hunt pass over `src/plugins/cmd/**`. Four verified bugs, each pinned by a test that failed before the fix:

1. `formatters::shorten_ago` replaced ` hour`/` minute`/` second` anywhere, so `docker ps` statuses `Up About an hour`, `Up About a minute` and `Up Less than a second` became `About anh`, `About am`, `Less than as`. Now only a number followed by a unit shortens (`2 hours` → `2h`).
2. `formatters::kubectl_get` picked cells by whitespace-token index. kubectl 1.22+ prints RESTARTS as `1 (3m ago)` (three tokens), so `-o wide` printed `ago)` as the pod IP. Cells now come from the header's column offsets; a row that does not line up falls back to token order.
3. `rules::apply` folded every remaining line into the trailer count when the budget ran out with a trace block still ahead, then counted those lines again as they came: shown lines plus trailer counts exceeded the input (18 vs 13). The fold now happens only when it also stops.
4. With no archive (store unavailable, `filter::compress_only`), the marker read `… N lines omitted (expand )`. It now names no id.

Check: new `shorten_ago_leaves_docker_prose_durations_whole`, `kubectl_get_wide_keeps_ip_after_a_restart_with_age`, `omitted_counts_add_up_with_a_trace_past_the_budget` (4 cases), `omitted_marker_without_an_archive_id_has_no_empty_expand`; `just check` green.

Status: done 2026-10-01
Model: Claude Code / claude-opus-5-5
