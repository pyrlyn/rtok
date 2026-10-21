Best competitor: lean-ctx mise.rs. Design: drop install/progress lines, keep task header + failing task output; `mise run` = inner command formatter.

Check: #1076 harness (`bench/c14`) shows ≥ lean-ctx on this category with full anchor retention.

Plan: `mise` formatter: drop install/progress lines, keep the task header and a failing task's output, delegate `mise run <task>` output to the inner command formatter via the existing echoed-line peel in `formatters.rs`. Tests: golden `mise_*` fixtures, error lines and exit line kept.

Result: new `src/plugins/cmd/mise.rs` (`tidy`): drops the `mise <tool>@<ver> download|checksum|extract|verify|install …` step lines and progress-bar / byte-counter frames (last `\r` frame, ANSI stripped) and says how many went in one `mise: N install/progress lines dropped` line. Lines with error/warn/fail/panic are never dropped, nor are `✓ installed`, the `[task] $ cmd` echo or the task's own output. `compress` tidies a `mise` command's output first and then runs the inner command's formatter or rule (`mise run` = inner formatter, via the existing echoed-line peel); `mise` is a formatter stem with an arm and goldens. Measured by golden bytes (the #1076 bench/c14 harness is not on main, so no lean-ctx comparison was run): `mise_install` 1726 -> 375 B (78 %, floor 70), `mise_run_install` (install preamble + failing `cargo test`) 1058 -> 55 B (95 %, floor 90) with the failing test name and the exit-relevant summary kept. Not done: no live run against real `mise install` output.

Status: done 2026-10-16
Model: Claude Code / claude-sonnet-5-5
