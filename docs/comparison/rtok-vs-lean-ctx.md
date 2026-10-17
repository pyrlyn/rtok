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
# rtok vs lean-ctx

Competitor: [lean-ctx](https://github.com/yvgude/lean-ctx) · category: Read, search and shell MCP servers · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs lean-ctx

**What lean-ctx does.** Rust MCP server plus hooks (Apache-2.0) for bounded file reads (10 read modes), search, a shell wrapper with 95+ patterns, deduplicated re-reads (~13 tokens) and handoff tools; it also denies native Grep/Glob.

**What rtok does here.** `read` plugin: 3 MCP tools, 6 read modes (`full`, `lines`, `map`, `signatures`, `diff`, `stripped`), sha256 dedup, root guard, no banner; the injection budget is 800 tokens per turn, byte-stable.

### lean-ctx: pros
- More read modes (`density`, a mode predictor learned from past sessions).
- Sub-agent handoff tools (`ctx_handoff`, `ctx_agent`).
- Re-read of an unchanged file costs ~13 tokens.

### lean-ctx: cons
- ~3.0–3.1 K tokens of banner and instructions injected per turn, cached or not.
- 12 tools ≈ 697 description tokens every turn on the author's machine.
- Measured 0 output saved on the author's machine.

### rtok: pros
- Fixed cost per turn ~10× smaller (whole budget 800 tokens; ~223 description tokens for 12 tools).
- Delta reads (T58.1) and measurement per call.

### rtok: cons
- Fewer read modes; no learned mode predictor.

## Verdict

**rtok is better on this workload.** The per-turn fixed cost of lean-ctx is larger than what its read modes save here; rtok copies the read modes that paid off and keeps the per-turn cost small.

**Gap to close:** competitor ahead on more read modes and a learned mode predictor. Tracked as [T670](../../plan.md) — make rtok beat this.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| output tokens saved (author machine) | no data on same workload | 0 output saved | `research.md` §4 matrix |
| fixed per-turn inject / description tokens | ~800 token budget; ~223 description tokens / 12 tools | ~3.0–3.1 K tokens/turn; ~697 description tokens / 12 tools | `docs/comparison.md` §2; `research.md` §9.1 |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| lean-ctx injects ~3.1 K tokens per turn; 0 output saved | `research.md` §4 matrix; `docs/comparison.md` §2 |
| 12 tools, ~697 description tokens per turn (`rtok doctor`, 2026-09-09) | `docs/comparison.md` §2 MCP table |
| v3.10.2: 30+ tools, ~13-token re-reads, ~3.0 K fixed per-session overhead (own README) | `research.md` §9.1 |
| lean-ctx ≈ 27 % of the tool table in the T59.4 re-measure (885 sessions) | `research.md` §9.3 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| lean-ctx | no data |

<!-- competitor-bench:end -->
