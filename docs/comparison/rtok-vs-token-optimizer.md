# rtok vs token-optimizer v5.13.44 (git tag, github.com/alexgreensh/token-optimizer)

Measured with `bench/c14/run_b2_shell.py` in image `rtok-c14b` (`bench/c14/b2/Dockerfile`):
rtok built from `12ebeb972e2bea88bec939a9ca9b436db884de13`, input repo checked out at the same commit.
Every tool runs the same real command in a fresh copy of the repo after the same seeded setup
(30 edited files, 20 untracked files, a 2000-line `app.log`, equal mtimes), with a fresh `HOME`,
1 warmup + 3 runs, tokens by tiktoken `o200k_base`. Entry points: `rtok run <cmd>`,
`lean-ctx -c "<cmd>"`, `python3 bash_compress.py <cmd>` (token-optimizer), and
`<cmd> | sqz compress --cmd <bin>` (sqz has no run mode). There is no `cargo` in the image, so cargo
cases are not in this batch. "Kept" = share of the raw output's anchors (commit SHAs as 7-char
prefixes, file paths, WARN/ERROR lines) still literally present. Grouped listings (rtok's
`dir/ (N files): a, b …`) print paths in pieces and score low on this check even when the dir is listed.

| Command | Raw | rtok | token-optimizer | rtok saves | token-optimizer saves | rtok kept | token-optimizer kept |
|---|---:|---:|---:|---:|---:|---:|---:|
| `git status (dirty)` | 548 | 548 | 418 | 0.0 % | 23.7 % | 100% of 50 | 100% of 50 |
| `git log -n 100` | 41337 | 454 | 41337 | 98.9 % | 0.0 % | 3% of 114 | 100% of 114 |
| `git log --stat -n 20` | 6169 | 394 | 6169 | 93.6 % | 0.0 % | 9% of 128 | 100% of 128 |
| `git diff HEAD~5 HEAD` | 30619 | 30181 | 630 | 1.4 % | 97.9 % | 100% of 57 | 51% of 57 |
| `git diff (worktree)` | 2811 | 2397 | 587 | 14.7 % | 79.1 % | 100% of 30 | 100% of 30 |
| `find src -name *.rs` | 2251 | 686 | 2251 | 69.5 % | 0.0 % | 0% of 327 | 100% of 327 |
| `ls -la src/plugins/cmd` | 296 | 296 | 296 | 0.0 % | 0.0 % | n/a | n/a |
| `ls -laR src/plugins` | 3739 | 523 | 1474 | 86.0 % | 60.6 % | n/a | n/a |
| `grep -rn fn src/plugins/cmd` | 7101 | 1296 | 729 | 81.7 % | 89.7 % | 71% of 7 | 100% of 7 |
| `grep -rn TODO src` | 51 | 51 | 51 | 0.0 % | 0.0 % | 100% of 1 | 100% of 1 |
| `cat rules.rs` | 20433 | 2827 | not run: command is not eligible for compression | 86.2 % | — | 29% of 7 | — |
| `cat app.log (repetitive)` | 48211 | 2654 | not run: command is not eligible for compression | 94.5 % | — | 18% of 154 | — |

The PyPI package `token-optimizer` 0.1.0 is a different project (avikahana/token-optimizer). The real tool
ships as a Claude Code plugin. Its PostToolUse Bash compressor `skills/token-optimizer/scripts/bash_compress.py`
runs the command itself (`shell=False`) and is used here as is.

Verdict: token-optimizer compresses only an allow-list of commands. It leaves `git log` and `find`
untouched (0 %) and refuses `cat` ("command is not eligible for compression"). On diffs it is the most
aggressive tool (−97.9 % on `git diff HEAD~5`) but keeps only 51 % of the changed paths. On the worktree diff
it keeps every path at −79 %. It wins `grep -rn` (−89.7 %). Its `git diff` and `ls -laR` outputs differed by a
few tokens between fresh runs; the median is shown.

What rtok needs: a summary mode for large diffs (file headers, hunk headers, change counts). Not done yet.
