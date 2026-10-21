Best competitor: caveman repetition. Design: loop output: normalise numbers/ids per line, collapse runs of same-shape lines into `shape ×N (vals: a..b)`; never collapse lines with error words.

Check: #1076 harness (`bench/c14`) shows ≥ 80 % on polling loops on this category with full anchor retention.

Plan: new loop-output collapser in `compact.rs` used for `for`/`while`/`until` scripts: normalise digits/ids per line, collapse runs of same-shape lines into `shape ×N (vals: a..b)`, never collapse a line with an error word. Tests: golden polling-loop fixture with `min_saving: 80`, unit test that error lines stay verbatim.

Result: new `src/plugins/cmd/loops.rs` (`collapse`): each line is cut at its number / hex-id / UUID fields; runs of 3+ lines with the same cut (blank lines between them do not end a run) become `shape ×N (vals: a..b)` with constant fields written back and changing ones as `#`, ranges smallest..largest for numbers and first..last for ids. A line with an error word (error, fail, warn, panic, exception, denied, refused, timeout, fatal, unreachable) is never collapsed and ends a run. `compress` runs it first when the command starts with `for`/`while`/`until` (a loop over two programs is a mixed chain that `[script]` would take), then the usual rule or formatter sees the short text; `for`/`while`/`until` are formatter stems with arms. Measured by golden bytes (the #1076 bench/c14 harness is not on main, so no caveman comparison was run): `for_poll` 2379 -> 281 B (88 %, floor 85, the one `curl: (7) … refused` line verbatim), `until_pending` 355 -> 20 B (94 %, floor 90), `while_check` 2572 -> 110 B (96 %, floor 90, the FAILED line verbatim). Not done: loops interleaving two or more line shapes (A, B, A, B) are not collapsed as a cycle.

Status: done 2026-10-16
Model: Claude Code / claude-sonnet-5-5
