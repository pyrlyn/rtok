# rtok vs lean-ctx 3.11.4 (release binary `lean-ctx-aarch64-unknown-linux-musl`, github.com/yvgude/lean-ctx)

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

| Command | Raw | rtok | lean-ctx | rtok saves | lean-ctx saves | rtok kept | lean-ctx kept |
|---|---:|---:|---:|---:|---:|---:|---:|
| `git status (dirty)` | 548 | 548 | 326 | 0.0 % | 40.5 % | 100% of 50 | 100% of 50 |
| `git log -n 100` | 41337 | 454 | 2399 | 98.9 % | 94.2 % | 3% of 114 | 89% of 114 |
| `git log --stat -n 20` | 6169 | 394 | 460 | 93.6 % | 92.5 % | 9% of 128 | 16% of 128 |
| `git diff HEAD~5 HEAD` | 30619 | 30181 | 25974 | 1.4 % | 15.2 % | 100% of 57 | 93% of 57 |
| `git diff (worktree)` | 2811 | 2397 | 2311 | 14.7 % | 17.8 % | 100% of 30 | 100% of 30 |
| `find src -name *.rs` | 2251 | 686 | 2251 | 69.5 % | 0.0 % | 0% of 327 | 100% of 327 |
| `ls -la src/plugins/cmd` | 296 | 296 | 99 | 0.0 % | 66.6 % | n/a | n/a |
| `ls -laR src/plugins` | 3739 | 523 | 988 | 86.0 % | 73.6 % | n/a | n/a |
| `grep -rn fn src/plugins/cmd` | 7101 | 1296 | 742 | 81.7 % | 89.6 % | 71% of 7 | 100% of 7 |
| `grep -rn TODO src` | 51 | 51 | 51 | 0.0 % | 0.0 % | 100% of 1 | 100% of 1 |
| `cat rules.rs` | 20433 | 2827 | 1756 | 86.2 % | 91.4 % | 29% of 7 | 29% of 7 |
| `cat app.log (repetitive)` | 48211 | 2654 | 6061 | 94.5 % | 87.4 % | 18% of 154 | 45% of 154 |

Verdict: lean-ctx is the strongest shell competitor so far:
- `git status`: −40.5 % (`unstaged: ~path …`).
- `ls -la`: −66.6 % (name and size only).
- `grep -rn`: −89.6 %.
- `cat` of a source file: −91.4 %.
- `git log -n 100`: keeps 89 % of commit SHAs by printing one line per commit.

rtok's output is smaller on `ls -laR`, `find` (lean-ctx saves 0 %) and the repetitive log (−94.5 % vs −87.4 %).
Its smaller `git log` outputs came from dropping most commits (see Kept), which #1079 fixes.
On that log lean-ctx keeps more distinct WARN/ERROR lines (45 % vs 18 %).

Added to rtok: [#1079](https://github.com/pyrlyn/rtok/pull/1079), git log one line per commit (the lean-ctx case). After [#1079](https://github.com/pyrlyn/rtok/pull/1079) (git log one line per commit), on the same raw bytes:
`git log -n 100` 454 → 2801 tokens, SHAs kept 3 % → 89 %; `git log --stat -n 20` 394 → 2910, kept 9 % → 99 %.

What rtok still needs:
- `git status` compacted into change groups;
- `ls -la` without permission, owner and date columns;
- a repetitive-log fold that keeps every distinct WARN/ERROR message.
