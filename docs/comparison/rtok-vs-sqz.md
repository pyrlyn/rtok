# rtok vs sqz 2.1.1 (release binary `sqz-v2.1.1-aarch64-unknown-linux-musl`, github.com/ojuschugh1/sqz)

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

| Command | Raw | rtok | sqz | rtok saves | sqz saves | rtok kept | sqz kept |
|---|---:|---:|---:|---:|---:|---:|---:|
| `git status (dirty)` | 548 | 548 | 548 | 0.0 % | 0.0 % | 100% of 50 | 100% of 50 |
| `git log -n 100` | 41337 | 454 | 18437 | 98.9 % | 55.4 % | 3% of 114 | 86% of 114 |
| `git log --stat -n 20` | 6169 | 394 | 3303 | 93.6 % | 46.5 % | 9% of 128 | 18% of 128 |
| `git diff HEAD~5 HEAD` | 30619 | 30181 | 17010 | 1.4 % | 44.4 % | 100% of 57 | 93% of 57 |
| `git diff (worktree)` | 2811 | 2397 | 1487 | 14.7 % | 47.1 % | 100% of 30 | 50% of 30 |
| `find src -name *.rs` | 2251 | 686 | 2251 | 69.5 % | 0.0 % | 0% of 327 | 100% of 327 |
| `ls -la src/plugins/cmd` | 296 | 296 | 296 | 0.0 % | 0.0 % | n/a | n/a |
| `ls -laR src/plugins` | 3739 | 523 | 915 | 86.0 % | 75.5 % | n/a | n/a |
| `grep -rn fn src/plugins/cmd` | 7101 | 1296 | 4872 | 81.7 % | 31.4 % | 71% of 7 | 100% of 7 |
| `grep -rn TODO src` | 51 | 51 | 51 | 0.0 % | 0.0 % | 100% of 1 | 100% of 1 |
| `cat rules.rs` | 20433 | 2827 | 20433 | 86.2 % | 0.0 % | 29% of 7 | 100% of 7 |
| `cat app.log (repetitive)` | 48211 | 2654 | 41054 | 94.5 % | 14.8 % | 18% of 154 | 6% of 154 |

`cargo install sqz` installs a different crate (`sqz` 0.1.0 from sequoia-pgp). The real install route is the
GitHub release binary, used here.

Verdict: sqz is a generic text compressor (n-gram abbreviation `«A1»`, dedup refs). It never drops whole
sections, so it keeps most anchors but saves less. It beats rtok only on diffs (−44 % on `git diff HEAD~5`,
where rtok saves 1.4 %), and loses on logs, listings, `find` and `cat` (0 % on both). Its own docs warn the
abbreviator can corrupt repeated SHAs/paths (`--no-abbrev`).

What rtok needs: a diff body compressor (rtok keeps the diff almost whole). Not done yet.
