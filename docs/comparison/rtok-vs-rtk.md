# rtok vs rtk

Competitor: [rtk](https://github.com/rtk-ai/rtk) · category: Command-output filters · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs rtk

**What rtk does.** A Rust CLI with ~80 (v0.47+: 100+) hand-written filters for `git`, `cargo`, `npm`, test runners, containers and more, wired into the agent's `PreToolUse` hook: `rtk git status` runs the command and prints a shortened version. Apache-2.0, ~78 k stars (GitHub API, 2026-09-01).

**What rtok does here.** `rtok run <cmd>` / the `cmd` plugin: 28 TOML rule families plus per-family formatters; the raw output is archived to `~/.rtok/archive/<id>` before anything is cut (`rtok expand <id>` returns it), and before/after bytes go into the `measurements` table.

### rtk: pros
- Largest filter library in the field (100+ per-command filters by v0.47.0) — more commands keep their signal by meaning instead of by position.
- Very mature: five-figure stars, many hosts (30+ listed), Windows native hook.
- Single binary, no daemon; easy to adopt alone.

### rtk: cons
- Lossy: lines are dropped with no way to get them back.
- Its own meter is bytes/4 (`rtk gain`), not the provider bill.
- The only independent measurement (JetBrains) found it cost +7.6 % to 0 % on agentic work against a claimed 60–90 %; the maintainers now say it "cuts bash output, not necessarily the bill by 90 %".

### rtok: pros
- Lossless: every cut is reversible with `rtok expand <id>`.
- Every cut is a row in one SQLite ledger next to provider `usage`.
- Same filters reachable from hooks, MCP and the proxy; fail-open in ≤ 10 ms.

### rtok: cons
- Smaller library: 28 rule families + ten formatters vs rtk's ~80–100+; families without a rule are cut by position (head/tail), so an error line can fall in the gap.
- No grouping pass (files by directory, errors by type) yet (T64.1).

## Verdict

**rtok is better overall for agent work; rtk is better at filter coverage.** rtk shrinks more command families out of the box, but it is lossy and its only independent bill measurement is neutral-to-negative. rtok keeps the raw output, measures every cut, and fits the cache-aware stack. Pick rtk if you only want the biggest shell-output filter set and accept lossy cuts (`docs/comparison.md` §7 says the same). The head-to-head token and latency numbers below are still pending.

**Gap to close:** competitor ahead on filter coverage (~100+ filters vs rtok's 28 rule families). Tracked as [T661](../../plan.md) — make rtok beat this.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| bash bytes cut (author corpus, 5,725 cmds) | no data | 40.4 % (1.1 M of 2.8 M bytes) | `research.md` §2 A/B (T9.2) |
| claimed bash cut | no data | 60–90 % (claim) | `research.md` §2; rtk README |
| agentic bill (JetBrains) | no data | +7.6 % to 0 % (measured, worse/neutral) | `research.md` §1, §7; `docs/comparison.md` §2 |
| rtok hook p95 latency | 8.25 ms (overhead, not a cut %) | no data | `docs/comparison.md` §3 (Gate P17, 2026-09-09) |
| formatter bytes `docker ps` | 3147→1190 bytes (~62 %) | no data | `research.md` §9.3 (T58.5) |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| ~80 filters claimed 60–90 % savings; measured 40.4 % of bash bytes over 5,725 commands on the author's machine (1.1 M of 2.8 M bytes); `rtk read` only 6.4 %, grep 18.5 %, diff 90 % | `research.md` §2 A/B bench table (T9.2) and §4 matrix |
| JetBrains measured rtk at +7.6 % to 0 % on the bill for agentic work | `research.md` §1 and §7 (fact-check ledger); `docs/comparison.md` §2 "Command-output filters — rtk" |
| Bash output was 35 % of the author's tool-result tokens (1.00 M of 2.83 M) | `docs/comparison.md` §2 |
| v0.47.0 / 0.50.0-rc: 100+ filters; maintainers: "cuts bash output, not necessarily the bill by 90 %" | `research.md` §9.1, https://github.com/rtk-ai/rtk/releases |
| rtok one Rust process p95 8.25 ms per hook call; static binary 18.9 MiB | `docs/comparison.md` §3 (research.md §2, Gate P17, 2026-09-09) |
| rtok table formatters: `docker ps` 3147→1190, `kubectl get` 4542→1731, `ps aux` 2341→870 bytes | `research.md` §9.3 (T58.5) |

## Own measurements

Shared clean harness, `bench/harness` (README there lists the fairness rules). Reproduce with
`python3 bench/harness/run.py rtk --rtok-pr feat/rtk-parity-filters`.

* Workload: the rtok repo at `12ebeb972e2bea88bec939a9ca9b436db884de13`, the same commit for all three columns.
* `rtok` = main `12ebeb972e2b`; `rtok-pr` = rtok#1077 `0129fe203dda` (built before the narrowing fix 462d3f86, which does not change these cases); `rtk` = rtk 1.0.1 (release tarball, sha256-checked).
* Every command's real output is captured once and replayed byte-for-byte to every tool. Each call gets a fresh HOME/tool state. The tokenizer is tiktoken 0.12.0 o200k_base. Every tool runs every case twice and the outputs must match: all 29 cases were deterministic.
* Latency: median of 30 rounds in a seeded shuffled order after 3 warmups. It is the tool's overhead on a replayed command (includes a ~30 ms Python shim for every tool), not cargo or git time.
* Results: `bench/harness/results/rtk/20261014-212517/` (`results.json`, `samples/<case>/{raw,rtok,rtok-pr,rtk}.txt`).

### `cargo test`

| case | raw | rtok | rtok-pr | rtk | ms rtok / pr / rtk |
|---|---:|---:|---:|---:|---|
| `cargo_test_pass` | 1756 | 158 (91.0 %) | 100 (94.3 %) | 16 (99.1 %) | 67.19 / 71.37 / 65.28 |
| `cargo_test_fail` | 1751 | 164 (90.6 %) | 168 (90.4 %) | 136 (92.2 %) | 82.38 / 82.75 / 97.64 |
| **total** | 3507 | **322 (90.8 %)** | **268 (92.4 %)** | **152 (95.7 %)** | |

### `cargo build`

| case | raw | rtok | rtok-pr | rtk | ms rtok / pr / rtk |
|---|---:|---:|---:|---:|---|
| `cargo_build_noop` | 45 | 45 (0.0 %) | 45 (0.0 %) | 30 (33.3 %) | 91.21 / 96.06 / 110.43 |
| `cargo_build_incremental` | 45 | 45 (0.0 %) | 45 (0.0 %) | 30 (33.3 %) | 54.1 / 65.09 / 52.61 |
| `cargo_build_warning` | 167 | 166 (0.6 %) | 112 (32.9 %) | 103 (38.3 %) | 59.95 / 71.99 / 61.66 |
| `cargo_build_error` | 133 | 132 (0.8 %) | 132 (0.8 %) | 88 (33.8 %) | 64.22 / 66.56 / 59.08 |
| **total** | 390 | **388 (0.5 %)** | **334 (14.4 %)** | **251 (35.6 %)** | |

### `find`

| case | raw | rtok | rtok-pr | rtk | ms rtok / pr / rtk |
|---|---:|---:|---:|---:|---|
| `find_rs` | 4561 | 725 (84.1 %) | 725 (84.1 %) | 232 (94.9 %) | 83.09 / 64.85 / 85.33 |
| `find_src_files` | 807 | 363 (55.0 %) | 363 (55.0 %) | 171 (78.8 %) | 97.43 / 84.86 / 96.98 |
| `find_cargo_toml` | 107 | 233 (-117.8 %) | 107 (0.0 %) | 80 (25.2 %) | 82.02 / 81.09 / 53.88 |
| `find_dirs` | 389 | 161 (58.6 %) | 161 (58.6 %) | 106 (72.8 %) | 83.68 / 81.76 / 54.57 |
| **total** | 5864 | **1482 (74.7 %)** | **1356 (76.9 %)** | **589 (90.0 %)** | |

### `ls -la`

| case | raw | rtok | rtok-pr | rtk | ms rtok / pr / rtk |
|---|---:|---:|---:|---:|---|
| `ls_la_root` | 1586 | 591 (62.7 %) | 476 (70.0 %) | 425 (73.2 %) | 65.75 / 70.35 / 87.4 |
| `ls_la_subdir` | 285 | 285 (0.0 %) | 166 (41.8 %) | 112 (60.7 %) | 73.66 / 72.74 / 57.37 |
| `ls_la_crates` | 270 | 270 (0.0 %) | 138 (48.9 %) | 62 (77.0 %) | 47.78 / 48.0 / 38.9 |
| **total** | 2141 | **1146 (46.5 %)** | **780 (63.6 %)** | **599 (72.0 %)** | |

### `git status`

| case | raw | rtok | rtok-pr | rtk | ms rtok / pr / rtk |
|---|---:|---:|---:|---:|---|
| `git_status_clean` | 17 | 17 (0.0 %) | 17 (0.0 %) | 16 (5.9 %) | 77.26 / 82.83 / 79.82 |
| `git_status_dirty` | 127 | 146 (-15.0 %) | 119 (6.3 %) | 38 (70.1 %) | 60.98 / 56.56 / 72.49 |
| **total** | 144 | **163 (-13.2 %)** | **136 (5.6 %)** | **54 (62.5 %)** | |

### `git diff`

| case | raw | rtok | rtok-pr | rtk | ms rtok / pr / rtk |
|---|---:|---:|---:|---:|---|
| `git_diff` | 312 | 347 (-11.2 %) | 278 (10.9 %) | 249 (20.2 %) | 53.21 / 57.05 / 73.4 |
| `git_diff_stat` | 45 | 45 (0.0 %) | 45 (0.0 %) | 45 (0.0 %) | 49.5 / 44.46 / 49.71 |
| **total** | 357 | **392 (-9.8 %)** | **323 (9.5 %)** | **294 (17.6 %)** | |

### `git log`

| case | raw | rtok | rtok-pr | rtk | ms rtok / pr / rtk |
|---|---:|---:|---:|---:|---|
| `git_log_20` | 4027 | 449 (88.9 %) | 449 (88.9 %) | 1355 (66.4 %) | 51.06 / 49.09 / 44.92 |
| `git_log_oneline_50` | 1200 | 551 (54.1 %) | 551 (54.1 %) | 1200 (0.0 %) | 64.19 / 61.53 / 45.28 |
| `git_log_stat_5` | 1275 | 398 (68.8 %) | 398 (68.8 %) | 1275 (0.0 %) | 82.09 / 69.64 / 58.05 |
| **total** | 6502 | **1398 (78.5 %)** | **1398 (78.5 %)** | **3830 (41.1 %)** | |

### `grep`

| case | raw | rtok | rtok-pr | rtk | ms rtok / pr / rtk |
|---|---:|---:|---:|---:|---|
| `grep_fn_main` | 1158 | 689 (40.5 %) | 689 (40.5 %) | 1158 (0.0 %) | 81.32 / 76.31 / 75.49 |
| `grep_settings_src` | 1420 | 577 (59.4 %) | 577 (59.4 %) | 1420 (0.0 %) | 138.73 / 162.64 / 158.63 |
| **total** | 2578 | **1266 (50.9 %)** | **1266 (50.9 %)** | **2578 (0.0 %)** | |

### `rg`

| case | raw | rtok | rtok-pr | rtk | ms rtok / pr / rtk |
|---|---:|---:|---:|---:|---|
| `rg_pub_fn` | 521 | 521 (0.0 %) | 521 (0.0 %) | 521 (0.0 %) | 78.92 / 98.48 / 81.71 |
| `rg_files_with` | 150 | 283 (-88.7 %) | 150 (0.0 %) | 127 (15.3 %) | 98.09 / 89.77 / 106.4 |
| **total** | 671 | **804 (-19.8 %)** | **671 (0.0 %)** | **648 (3.4 %)** | |

### `cat`

| case | raw | rtok | rtok-pr | rtk | ms rtok / pr / rtk |
|---|---:|---:|---:|---:|---|
| `cat_rust` | 1469 | 517 (64.8 %) | 517 (64.8 %) | 1469 (0.0 %) | 78.06 / 75.89 / 43.66 |
| `cat_toml` | 4945 | 995 (79.9 %) | 995 (79.9 %) | 4945 (0.0 %) | 87.75 / 101.19 / 60.91 |
| **total** | 6414 | **1512 (76.4 %)** | **1512 (76.4 %)** | **6414 (0.0 %)** | |

### `tree`

| case | raw | rtok | rtok-pr | rtk | ms rtok / pr / rtk |
|---|---:|---:|---:|---:|---|
| `tree_plugins` | 65 | 65 (0.0 %) | 65 (0.0 %) | 58 (10.8 %) | 65.57 / 70.47 / 56.84 |
| `tree_crates_l2` | 302 | 379 (-25.5 %) | 379 (-25.5 %) | 295 (2.3 %) | 83.88 / 80.98 / 66.22 |
| **total** | 367 | **444 (-21.0 %)** | **444 (-21.0 %)** | **353 (3.8 %)** | |

### `wc`

| case | raw | rtok | rtok-pr | rtk | ms rtok / pr / rtk |
|---|---:|---:|---:|---:|---|
| `wc_l` | 33 | 33 (0.0 %) | 33 (0.0 %) | 24 (27.3 %) | 86.91 / 86.09 / 58.84 |
| **total** | 33 | **33 (0.0 %)** | **33 (0.0 %)** | **24 (27.3 %)** | |

### What differs, case by case

* **The trailer is most of the gap.** Each cut rtok output ends with `[rtok <64-hex> · N lines · expand: rtok expand <64-hex>]`, about 90 tokens. rtk prints `[full output: rtk recall <12-hex>]` or nothing. In `cargo_test_pass` rtok-pr's payload (`cargo test: 93 passed (2 suites)`) is ~9 tokens against rtk's 16, but the trailer brings the total to 100. Same story for `git_status_dirty` (payload ≈ rtk's), `ls_la_crates` and `cargo_build_warning`. Proposal: a 12-hex id printed once, with prefix lookup in `rtok expand`; that would win most of these cases.
* **cargo test**: rtk and rtok-pr both aggregate the suite totals into one line. On a failure, rtk keeps the whole `---- name stdout ----` block plus the `note: RUST_BACKTRACE` line and the raw `test result:` line. rtok-pr keeps only the panic location, message and left/right, plus `FAILED: names`.
* **cargo build**: rtk keeps the full rustc snippet (source line, carets, `= note`) and adds a `cargo build: N errors, M warnings` line. rtok keeps only the headline, the `-->` location and the `could not compile` verdict, so its payload is smaller but has less context. A noop or incremental build is under the 170-byte raw threshold (T175), so rtok prints it verbatim.
* **find**: rtk prints paths relative to the search root, `dir/ a b c` with every name, and adds an extension histogram. rtok's `group = "dir"` lists 3 names per dir plus `…` (lossy, archived) and repeats `(N files):`. rtok#1077 stops the regression on short finds (−117.8 % → 0 %). Next step: print single-file dirs as plain paths and drop `files` from the count.
* **ls -la**: rtk prints `755 name/` and `644 name 12.8K` one per line. rtok-pr prints every directory on one line and files as `name size`, which is a smaller payload. The trailer is the gap.
* **git status / diff**: rtok-pr now uses porcelain codes like rtk. For diff it keeps one `+++ b/path` per file, while rtk adds a `--stat` summary and per-file `+N -M`. Both regressions on main (−15 %, −11 %) are fixed.
* **git log, grep, cat**: rtok is far ahead. rtk passes `git log --oneline`, `--stat`, `grep -rn ... --include/--exclude-dir` and `rtk read` through unchanged (0 %).
* **tree, wc, rg -l**: rtk is slightly ahead. rtok's `tree` cut at 40 lines plus the trailer is longer than the raw output on `tree -L 2 crates` (still −25.5 %, open). `rg -l` is fixed to 0 % by never-worse.

