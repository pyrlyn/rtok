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

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `ok`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

| side | tokens saved (vs raw) |
|---|---|
| rtok | 78.42 % |
| rtk | 68.29 % |

| case | raw tok | rtk tok | rtok tok | rtk saved | rtok saved |
|---|---:|---:|---:|---:|---:|
| `git status` | 117 | 31 | 152 | 73.5 % | -29.9 % |
| `git log -n 50` | 5255 | 2095 | 326 | 60.1 % | 93.8 % |
| `git log --stat -n 10` | 1029 | 1029 | 315 | 0.0 % | 69.4 % |
| `git diff` | 160 | 118 | 222 | 26.2 % | -38.7 % |
| `ls -la` | 670 | 223 | 670 | 66.7 % | 0.0 % |
| `find . -name '*.rs' -not -path './target/*'` | 942 | 254 | 478 | 73.0 % | 49.3 % |
| `grep -rn 'fn main' --include=*.rs .` | 421 | 421 | 421 | 0.0 % | 0.0 % |
| `cargo build --locked` | 267 | 81 | 159 | 69.7 % | 40.4 % |
| `cargo test --locked` | 4598 | 16 | 162 | 99.7 % | 96.5 % |
| **total** | 13459 | 4268 | 2905 | 68.3 % | 78.4 % |

| case | tool | runs | median ms | p95 ms |
|---|---|---:|---:|---:|
| `cargo_build_locked` | cargo | 30 | 23.54 | 25.23 |
| `cargo_build_locked` | rtk | 30 | 26.93 | 28.15 |
| `cargo_build_locked` | rtok | 30 | 30.35 | 31.65 |
| `cargo_test_locked` | cargo | 30 | 917.31 | 924.78 |
| `cargo_test_locked` | rtk | 30 | 923.41 | 935.16 |
| `cargo_test_locked` | rtok | 30 | 929.53 | 941.04 |
| `find_name_rs_not_path_target` | find | 30 | 2.13 | 2.70 |
| `find_name_rs_not_path_target` | rtk | 30 | 5.85 | 6.28 |
| `find_name_rs_not_path_target` | rtok | 30 | 6.60 | 7.01 |
| `git_diff` | git | 30 | 1.16 | 1.37 |
| `git_diff` | rtk | 30 | 5.36 | 6.02 |
| `git_diff` | rtok | 30 | 6.07 | 7.71 |
| `git_log_n_50` | git | 30 | 1.06 | 1.13 |
| `git_log_n_50` | rtk | 30 | 3.70 | 4.16 |
| `git_log_n_50` | rtok | 30 | 6.04 | 6.79 |
| `git_log_stat_n_10` | git | 30 | 1.99 | 2.09 |
| `git_log_stat_n_10` | rtk | 30 | 4.38 | 4.69 |
| `git_log_stat_n_10` | rtok | 30 | 6.46 | 6.95 |
| `git_status` | git | 30 | 1.53 | 1.62 |
| `git_status` | rtk | 30 | 13.43 | 16.68 |
| `git_status` | rtok | 30 | 15.05 | 19.04 |
| `grep_rn_fn_main_include_rs` | grep | 30 | 3.88 | 4.92 |
| `grep_rn_fn_main_include_rs` | rtk | 30 | 6.61 | 7.83 |
| `grep_rn_fn_main_include_rs` | rtok | 30 | 8.76 | 9.78 |
| `ls_la` | ls | 30 | 0.52 | 0.72 |
| `ls_la` | rtk | 30 | 3.57 | 3.97 |
| `ls_la` | rtok | 30 | 5.56 | 13.58 |

Environment:

```text
platform: Darwin 27.0.0 arm64
python: 3.12.15
docker_run: --cpus 4 --memory 8g --memory-swap 8g
image: rtokbench/rtk
host_model: Mac15,9
host_cpu: Apple M3 Max
host_ncpu: 16
host_ram_bytes: 68719476736
macos: 27.0.1
colima: colima version v0.10.3
image_id: sha256:619f1aefcded01f808b50de961ec26558d9c5ef0a46d61bc970bb29ef67c3186
base_image: rust@sha256:a8a5f0a1e5fe7dfe1d352591e4a1c7dd2c08fd70475cae872cf3458ba0df0546
rtok: rtok 0.17.0 (78f63b381)
rtk: rtk 1.0.1
rustc: rustc 1.98.1 (48a229cea 2026-09-01)
cargo: cargo 1.98.1 (797e8a9bc 2026-08-05)
fixture_sha: 4649aa9700619f94cf9c66876e9549d83420e16c
tokenizer: tiktoken o200k_base via /opt/tok/bin/python3
```

<!-- competitor-bench:end -->
