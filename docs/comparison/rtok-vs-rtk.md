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

**rtok is better overall for agent work; rtk is better at filter coverage.** rtk shrinks more command families out of the box, but it is lossy and its only independent bill measurement is neutral-to-negative. rtok keeps the raw output, measures every cut, and fits the cache-aware stack. Pick rtk if you only want the biggest shell-output filter set and accept lossy cuts (`docs/comparison.md` §7 says the same). Head-to-head token numbers are in the sections below.

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

## What changed

Re-run on 2026-10-14 (Docker, Colima profile `rtok-bench`, 4 CPU / 8 GiB) of the shared harness: main `7a7666ad` vs rtok#1077 `0ef259b2` vs rtk 1.0.1.

* rtok-pr is never worse than the raw output: the never-worse guard prints the original when the trailer would make it longer (`find_cargo_toml`, `rg_files_with`, `tree_crates_l2`).
* The trailer is short (`[rtok 3bc88fd9efbd · 11 lines]`, about 15 tokens instead of about 90); `--no-ref` drops it.
* Largest gains over main: `cargo build` 0.5 % → 64.6 %, `git status` −13.2 % → 52.8 %, `git diff` −9.8 % → 29.1 %, `ls -la` 46.1 % → 73.7 %, `cargo test` 90.8 % → 96.6 %.

## Performance figures

Token savings per command, re-run 2026-10-14 (total over its cases, tiktoken o200k_base, higher is better; cells are output tokens and saving vs raw):

| Command | raw | rtok main | rtok PR | rtok PR `--no-ref` | rtk |
|---|---:|---:|---:|---:|---:|
| `cargo test` | 3507 | 322 (90.8%) | 119 (96.6%) | 88 (97.5%) | 151 (95.7%) |
| `cargo build` | 390 | 388 (0.5%) | 138 (64.6%) | 76 (80.5%) | 251 (35.6%) |
| `find` | 5864 | 1482 (74.7%) | 1148 (80.4%) | 1100 (81.2%) | 589 (90.0%) |
| `ls -la` | 2141 | 1155 (46.1%) | 564 (73.7%) | 518 (75.8%) | 599 (72.0%) |
| `git status` | 144 | 163 (-13.2%) | 68 (52.8%) | 54 (62.5%) | 54 (62.5%) |
| `git diff` | 357 | 392 (-9.8%) | 253 (29.1%) | 237 (33.6%) | 294 (17.6%) |
| `git log` | 6502 | 1398 (78.5%) | 1182 (81.8%) | 1132 (82.6%) | 3830 (41.1%) |
| `grep` | 2578 | 1266 (50.9%) | 1107 (57.1%) | 1076 (58.3%) | 2578 (0.0%) |
| `rg` | 671 | 808 (-20.4%) | 671 (0.0%) | 671 (0.0%) | 648 (3.4%) |
| `cat` | 6414 | 1512 (76.4%) | 1359 (78.8%) | 1326 (79.3%) | 6414 (0.0%) |
| `tree` | 367 | 444 (-21.0%) | 367 (0.0%) | 360 (1.9%) | 353 (3.8%) |
| `wc` | 33 | 33 (0.0%) | 33 (0.0%) | 33 (0.0%) | 24 (27.3%) |

Latency is flat (re-run 2026-10-14, about 31–35 ms for every tool): it is dominated by the ~30 ms Python replay shim, so it says nothing about filter speed.

## Command table

One table per command; each cell is output tokens (saving vs raw).

### `cargo test` (re-run 2026-10-14)

| case | raw | rtok | rtok-pr | rtok-pr `--no-ref` | rtk |
|---|---:|---:|---:|---:|---:|
| `cargo_test_pass` | 1756 | 158 (91.0%) | 24 (98.6%) | 10 (99.4%) | 16 (99.1%) |
| `cargo_test_fail` | 1751 | 164 (90.6%) | 95 (94.6%) | 78 (95.5%) | 135 (92.3%) (nondet!) |
| **total** | 3507 | 322 (90.8%) | 119 (96.6%) | 88 (97.5%) | 151 (95.7%) |

### `cargo build` (re-run 2026-10-14)

| case | raw | rtok | rtok-pr | rtok-pr `--no-ref` | rtk |
|---|---:|---:|---:|---:|---:|
| `cargo_build_noop` | 45 | 45 (0.0%) | 19 (57.8%) | 3 (93.3%) | 30 (33.3%) |
| `cargo_build_incremental` | 45 | 45 (0.0%) | 19 (57.8%) | 3 (93.3%) | 30 (33.3%) |
| `cargo_build_warning` | 167 | 166 (0.6%) | 40 (76.0%) | 26 (84.4%) | 103 (38.3%) |
| `cargo_build_error` | 133 | 132 (0.8%) | 60 (54.9%) | 44 (66.9%) | 88 (33.8%) |
| **total** | 390 | 388 (0.5%) | 138 (64.6%) | 76 (80.5%) | 251 (35.6%) |

### `find` (re-run 2026-10-14)

| case | raw | rtok | rtok-pr | rtok-pr `--no-ref` | rtk |
|---|---:|---:|---:|---:|---:|
| `find_rs` | 4561 | 725 (84.1%) | 659 (85.6%) | 643 (85.9%) | 232 (94.9%) |
| `find_src_files` | 807 | 363 (55.0%) | 288 (64.3%) | 271 (66.4%) | 171 (78.8%) |
| `find_cargo_toml` | 107 | 233 (-117.8%) | 107 (0.0%) | 107 (0.0%) | 80 (25.2%) |
| `find_dirs` | 389 | 161 (58.6%) | 94 (75.8%) | 79 (79.7%) | 106 (72.8%) |
| **total** | 5864 | 1482 (74.7%) | 1148 (80.4%) | 1100 (81.2%) | 589 (90.0%) |

### `ls -la` (re-run 2026-10-14)

| case | raw | rtok | rtok-pr | rtok-pr `--no-ref` | rtk |
|---|---:|---:|---:|---:|---:|
| `ls_la_root` | 1586 | 600 (62.2%) | 407 (74.3%) | 390 (75.4%) | 425 (73.2%) |
| `ls_la_subdir` | 285 | 285 (0.0%) | 98 (65.6%) | 82 (71.2%) | 112 (60.7%) |
| `ls_la_crates` | 270 | 270 (0.0%) | 59 (78.1%) | 46 (83.0%) | 62 (77.0%) |
| **total** | 2141 | 1155 (46.1%) | 564 (73.7%) | 518 (75.8%) | 599 (72.0%) |

### `git status` (re-run 2026-10-14)

| case | raw | rtok | rtok-pr | rtok-pr `--no-ref` | rtk |
|---|---:|---:|---:|---:|---:|
| `git_status_clean` | 17 | 17 (0.0%) | 17 (0.0%) | 17 (0.0%) | 16 (5.9%) |
| `git_status_dirty` | 127 | 146 (-15.0%) | 51 (59.8%) | 37 (70.9%) | 38 (70.1%) |
| **total** | 144 | 163 (-13.2%) | 68 (52.8%) | 54 (62.5%) | 54 (62.5%) |

### `git diff` (re-run 2026-10-14)

| case | raw | rtok | rtok-pr | rtok-pr `--no-ref` | rtk |
|---|---:|---:|---:|---:|---:|
| `git_diff` | 312 | 347 (-11.2%) | 208 (33.3%) | 192 (38.5%) | 249 (20.2%) |
| `git_diff_stat` | 45 | 45 (0.0%) | 45 (0.0%) | 45 (0.0%) | 45 (0.0%) |
| **total** | 357 | 392 (-9.8%) | 253 (29.1%) | 237 (33.6%) | 294 (17.6%) |

### `git log` (re-run 2026-10-14)

| case | raw | rtok | rtok-pr | rtok-pr `--no-ref` | rtk |
|---|---:|---:|---:|---:|---:|
| `git_log_20` | 4027 | 449 (88.9%) | 383 (90.5%) | 367 (90.9%) | 1355 (66.4%) |
| `git_log_oneline_50` | 1200 | 551 (54.1%) | 476 (60.3%) | 459 (61.8%) | 1200 (0.0%) |
| `git_log_stat_5` | 1275 | 398 (68.8%) | 323 (74.7%) | 306 (76.0%) | 1275 (0.0%) |
| **total** | 6502 | 1398 (78.5%) | 1182 (81.8%) | 1132 (82.6%) | 3830 (41.1%) |

### `grep` (re-run 2026-10-14)

| case | raw | rtok | rtok-pr | rtok-pr `--no-ref` | rtk |
|---|---:|---:|---:|---:|---:|
| `grep_fn_main` | 1158 | 689 (40.5%) | 606 (47.7%) | 591 (49.0%) | 1158 (0.0%) |
| `grep_settings_src` | 1420 | 577 (59.4%) | 501 (64.7%) | 485 (65.8%) | 1420 (0.0%) |
| **total** | 2578 | 1266 (50.9%) | 1107 (57.1%) | 1076 (58.3%) | 2578 (0.0%) |

### `rg` (re-run 2026-10-14)

| case | raw | rtok | rtok-pr | rtok-pr `--no-ref` | rtk |
|---|---:|---:|---:|---:|---:|
| `rg_pub_fn` | 521 | 521 (0.0%) | 521 (0.0%) | 521 (0.0%) | 521 (0.0%) |
| `rg_files_with` | 150 | 287 (-91.3%) | 150 (0.0%) | 150 (0.0%) | 127 (15.3%) |
| **total** | 671 | 808 (-20.4%) | 671 (0.0%) | 671 (0.0%) | 648 (3.4%) |

### `cat` (re-run 2026-10-14)

| case | raw | rtok | rtok-pr | rtok-pr `--no-ref` | rtk |
|---|---:|---:|---:|---:|---:|
| `cat_rust` | 1469 | 517 (64.8%) | 439 (70.1%) | 423 (71.2%) | 1469 (0.0%) |
| `cat_toml` | 4945 | 995 (79.9%) | 920 (81.4%) | 903 (81.7%) | 4945 (0.0%) |
| **total** | 6414 | 1512 (76.4%) | 1359 (78.8%) | 1326 (79.3%) | 6414 (0.0%) |

### `tree` (re-run 2026-10-14)

| case | raw | rtok | rtok-pr | rtok-pr `--no-ref` | rtk |
|---|---:|---:|---:|---:|---:|
| `tree_plugins` | 65 | 65 (0.0%) | 65 (0.0%) | 65 (0.0%) | 58 (10.8%) |
| `tree_crates_l2` | 302 | 379 (-25.5%) | 302 (0.0%) | 295 (2.3%) | 295 (2.3%) |
| **total** | 367 | 444 (-21.0%) | 367 (0.0%) | 360 (1.9%) | 353 (3.8%) |

### `wc` (re-run 2026-10-14)

| case | raw | rtok | rtok-pr | rtok-pr `--no-ref` | rtk |
|---|---:|---:|---:|---:|---:|
| `wc_l` | 33 | 33 (0.0%) | 33 (0.0%) | 33 (0.0%) | 24 (27.3%) |
| **total** | 33 | 33 (0.0%) | 33 (0.0%) | 33 (0.0%) | 24 (27.3%) |
## Where rtok wins / loses (re-run 2026-10-14)

**Wins (rtok-pr vs rtk, default output):**

* `cargo test` 96.6 % vs 95.7 % (97.5 % with `--no-ref`).
* `cargo build` 64.6 % vs 35.6 %.
* `git log` 81.8 % vs 41.1 %: rtk passes `--oneline` and `--stat` through unchanged.
* `ls -la` 73.7 % vs 72.0 %.
* `git diff` 29.1 % vs 17.6 %.
* `grep` 57.1 % vs 0.0 % and `cat` 78.8 % vs 0.0 %: rtk has no filter for them.

**Losses (rtok-pr vs rtk; the `WARN NEGATIVE` lines of the run):**

* `find` 80.4 % vs 90.0 % (`find_rs` 85.6 vs 94.9, `find_src_files` 64.3 vs 78.8, `find_cargo_toml` 0.0 vs 25.2).
* `git status` 52.8 % vs 62.5 %: dirty 59.8 % vs 70.1 % (ties with `--no-ref`, 70.9 %); clean 0.0 % vs 5.9 % (17 vs 16 tokens).
* `tree` 0.0 % vs 3.8 % (`tree_plugins` 0.0 vs 10.8).
* `rg` 0.0 % vs 3.4 % (`rg_files_with` 0.0 vs 15.3).
* `wc -l` 0.0 % vs 27.3 %.

No case is worse than raw output for rtok-pr. Main still has negatives: `find_cargo_toml` −117.8 %, `rg_files_with` −91.3 %, `tree_crates_l2` −25.5 %, `git_status_dirty` −15.0 %, `git_diff` −11.2 %.

Caveat: the rtk output for `cargo_test_fail` was not byte-identical across the two determinism runs (`nondet!`), so its 92.3 % there is approximate.

## Methodology

Shared clean harness, `bench/harness` (its README lists the fairness rules). Reproduce with
`python3 bench/harness/run.py rtk --rtok-ref origin/main --rtok-pr origin/feat/rtk-parity-filters`.

* Workload: the rtok repo at `12ebeb972e2bea88bec939a9ca9b436db884de13`, identical for all columns.
* `rtok` = main `7a7666ad`; `rtok-pr` = rtok#1077 `0ef259b2`; `rtok-pr --no-ref` = same build without the trailer; `rtk` = rtk 1.0.1 (release tarball, sha256-checked).
* Every command's real output is captured once and replayed byte-for-byte to every tool, in one container (`--cpus 4 --memory 8g --network none`), fresh HOME per call.
* Tokenizer: tiktoken 0.12.0 o200k_base. 30 timing rounds after 3 warmups, seeded shuffle.
* Results: `bench/harness/results/rtk/20261014-231100/` (`results.json`, `summary.md`, `negatives.txt`, `samples/`).
* Fixture test: `cargo test --test bench_fixtures` (29 stored outputs, no Docker or network needed), also run inside a container.

## Filter coverage (rtk vs rtok rule families)

Checked 2026-10-15. rtk side: tag [v1.0.1](https://github.com/rtk-ai/rtk/releases/tag/v1.0.1) (published 2026-10-14), `src/filters/*.toml` (62 declarative filters) and `src/cmds/**` (58 command modules), read through the GitHub tree API. rtok side: `FORMATTER_STEMS` in `src/plugins/cmd/formatters.rs` and the tables in `rules/default.toml` at `1932bc928`. "Rule" means a named `[family]` table, "formatter" a Rust formatter; a family without either falls to the default head/tail cut. This lists what exists, not how well it filters: sizes are in the harness results above.

| Area | rtk (filter or module) | rtok | Gap |
|---|---|---|---|
| Version control | `git`, `diff`, `gh`, `glab`, `gt`, `jj`, `yadm` | `git` formatter, `gh` rule | `glab`, `gt`, `jj`, `yadm` |
| Rust | `cargo` (+ runner) | `cargo` formatter and rule | none |
| Go | `go`, `golangci-lint` | `go` formatter and rule | `golangci-lint` |
| JavaScript | `npm`, `pnpm`, `bun`, `deno`, `next`, `nx`, `turbo`, `playwright`, `prettier`, `prisma`, `tsc`, `vitest`, lint, `biome`, `oxlint` | `npm`, `pnpm`, `node`, `tsc`, `eslint` rules; `jest`, `vitest` formatters | `bun`, `deno`, `next`, `nx`, `turbo`, `playwright`, `prettier`, `prisma`, `biome`, `oxlint` |
| Python | `pytest`, `pip`, `uv`, `mypy`, `ruff`, `sqlfluff`, `poetry-install`, `basedpyright`, `ty`, `uv-sync` | `pytest` formatter; `pip`, `uv`, `python`, `python3` rules | `mypy`, `ruff`, `sqlfluff`, `poetry`, `basedpyright`, `ty` |
| JVM and .NET | `mvn`, `gradlew`, `sbt`, `dotnet` (build, format, trx), `spring-boot` | `mvn`, `gradle`, `dotnet` rules | `sbt`, `spring-boot`, `dotnet` format/trx |
| PHP and Ruby | `artisan`, `ecs`, `paratest`, `pest`, `phpstan`, `phpunit`, `pint`, `rake`, `rspec`, `rubocop`, `bundle-install`, `composer-install`, `mix` | none | all |
| C, C++ and Apple | `gcc`, `ctest`, `xcodebuild`, `swift-build`, `pio-run`, `make` | `make`, `cmake` rules | `gcc`, `ctest`, `xcodebuild`, `swift-build`, `pio-run` |
| Containers and cloud | `docker`/`container`, `kubectl`, `helm`, `aws`, `gcloud`, `skopeo`, `hadolint`, `terraform`, `tofu`, `pulumi`, `ansible-playbook`, `sops` | `docker`, `kubectl` formatters; `aws` rule | `helm`, `gcloud`, `skopeo`, `hadolint`, `terraform`/`tofu`, `pulumi`, `ansible-playbook`, `sops` |
| System | `ls`, `find`, `tree`, `df`, `du`, `ps`, `stat`, `wc`, `env`, `ping`, `ssh`, `rsync`, `systemctl-status`, `iptables`, `fail2ban-client`, `curl`, `wget`, `psql`, `jq` | `ls`, `find`, `curl` rules; `tree`, `df`, `ps` formatters | `du`, `stat`, `wc`, `env`, `ping`, `ssh`, `rsync`, `systemctl`, `iptables`, `fail2ban-client`, `wget`, `psql`, `jq` |
| Search and files | `search`, `read`, `log` | `grep`, `rg`, `sed`, `cat`, `awk` rules | none (`log` is covered by the shared head/tail/dedupe pass) |
| Lint and CI | `shellcheck`, `yamllint`, `markdownlint`, `pre-commit`, `trunk-build`, `liquibase` | none | all |
| Task runners | `just`, `mise`, `task` | wrapper peel (`just -c`, `mise exec`, echoed recipe line) | `task` |
| Misc | `jira`, `ollama`, `shopify-theme`, `quarto-render`, `brew-install` | `brew`, `apt` rules, `sqlite3` formatter | `jira`, `ollama`, `shopify-theme`, `quarto-render` |

Where rtok is broader: `sqlite3`, `awk` and `apt` have no rtk counterpart in the tree; every rtok filter writes a `Measurement` row, archives the raw output for `rtok expand`, and runs from hooks, MCP and the proxy, which rtk's filters do not. Where rtk is broader: the whole PHP/Ruby/lint/IaC block and most JavaScript and Python tooling above. The gap list is not a to-do list: a family is worth a rule only if agents run it. `rtok discover` ranks exactly that from the local store:

```
rtok discover --since 30d --top 15
```

It reads the recorded `PostToolUse` Bash bodies, takes each command's family (`cd`, `FOO=1`, `mise exec`, `uv run` and an `rtok run --` wrapper peeled), drops the families that already have a formatter or a named rule, and lists the rest by the output tokens that reached the model. Bodies over `core.call_io_inline_bytes` are not stored inline, so the biggest outputs can be missing and the numbers are a floor.

### Savings, sourced only

| Case | rtok saved | rtk saved | Source |
|---|---|---|---|
| bash bytes cut (author corpus, 5,725 cmds) | no data | 40.4 % (1.1 M of 2.8 M bytes) | `research.md` §2 A/B (T9.2) |
| claimed bash cut | no data | 60–90 % (claim) | `research.md` §2 |
| agentic bill (JetBrains) | no data | +7.6 % to 0 % (worse/neutral) | `research.md` §1, §7 |
| head-to-head harness (`bench/competitors/rtk`) | see "Results" above | see "Results" above | `bench/harness/results/rtk/20261014-231100/summary.md` |

## Competitor landscape

Measured in the same harness family on 2026-10-14 (draft #1076, commit `a449e6a3`; rtok = main at that time). Savings are token savings vs raw. Techniques and licences: `docs/research/competitors-techniques.md`.

| Case | rtok | Best competitor |
|---|---:|---|
| git status (dirty) | 0.0 % | lean-ctx 40.5 % |
| git diff HEAD~5 | 1.4 % | token-optimizer 97.9 % (keeps 51 % of paths), sqz 44.4 % (93 % of paths) |
| git diff worktree | 14.7 % | token-optimizer 79.1 % (100 % of paths) |
| ls -la | 0.0 % | lean-ctx 66.6 % |
| grep -rn | 81.7 % (71 % anchors kept) | token-optimizer 89.7 %, lean-ctx 89.6 % (100 % anchors) |
| cat source file | 86.2 % | lean-ctx 91.4 % |
| MCP tool catalogs | 0 % | caveman 10–29 % |

In that run (2026-10-14) rtok led on `git log` (after #1079, which keeps 89 % of commit hashes), `find`, `ls -laR` and `cat app.log`. Targets for the open gaps are tasks T672–T686 in `plan.md`; they are targets, not measurements.

Sources studied: sqz (MIT OR Apache-2.0, code read), lean-ctx (Apache-2.0 for `rust/**`, code read), caveman (Apache-2.0, code read), token-optimizer (PolyForm Noncommercial, code **not** read; behaviour and documentation only).

## How to reproduce

Requirements: Python 3.10+ and a working `docker` CLI. On macOS use a Colima profile (here `rtok-bench`, 4 CPU / 8 GiB) and point `DOCKER_HOST` at its socket.

```
python3 bench/harness/run.py rtk --rtok-ref origin/main --rtok-pr origin/feat/rtk-parity-filters
```

* `run.py` exits with code 3 and prints `WARN NEGATIVE` lines when rtok-pr saves less than the competitor on any command or case; `--no-fail-on-negative` forces exit 0.
* Fixture test, no Docker or network needed: `cargo test --test bench_fixtures` (29 stored outputs, a floor per case, output never longer than raw). It also passed inside a container in this run.
* Refresh fixtures from a new capture: `python3 bench/harness/run.py rtk --refresh-fixtures <dir>`.
* The run took about 12 minutes on the Mac with cached images.
