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

Not run yet. The harness is written and committed, but on 2026-10-14 the host disk (`/System/Volumes/Data`) had 128 MiB free (100 % used), and the Colima VM failed to pull the base image with `input/output error`. No number is reported until the script runs.

Harness: [`bench/competitors/rtk/`](../../bench/competitors/rtk/) (`run.sh` on the macOS host, `Dockerfile`, `bench.sh` inside the container; shared `bench/competitors/_lib/`).

What it measures, per command (same command three ways: raw, `rtk <cmd>`, `rtok run <cmd>`):

- tokens of the output with tiktoken `o200k_base` (fixed tokenizer, pinned `tiktoken==0.12.0`), and the cut against raw;
- wall-clock latency with hyperfine, 3 warm-ups + 30 runs, median and p95.

Commands: `git status` (fixed dirty tree), `git log -n 50`, `git log --stat -n 10`, `git diff`, `ls -la`, `find . -name *.rs`, `grep -rn "fn main"`, `cargo build --locked` (warm), `cargo test --locked` (warm).

Fixture corpus: `BurntSushi/ripgrep` at tag `14.1.1` (the commit SHA is written to `versions.txt` on each run), built once in the image so cargo replays are warm; container runs with `--network none`.

Note: rtok archives the raw output on every call (the lossless path), so its latency includes that write; rtk does not archive.

### Environment (to be filled from `results/<run>/env.txt` and `versions.txt` on the first run)

| Item | Value |
|---|---|
| Host | Mac15,9, Apple M3 Max, 64 GiB RAM (`sysctl hw.model machdep.cpu.brand_string hw.memsize`, 2026-10-14) |
| Colima | v0.10.3 (git 00f6c297), profile `rtok-bench`, VM type `vz`, aarch64, runtime docker |
| VM | 4 CPUs, 8 GiB memory, 60 GiB disk |
| Container | `docker run --cpus 4 --memory 8g --memory-swap 8g --network none` |
| Image | `rust:1.98.1-bookworm`, resolved to a digest by `run.sh` (recorded as `bench.base` label) |
| rtok | this checkout, `cargo build --release --locked --bin rtok`, default features, `RUSTFLAGS` empty, `CARGO_INCREMENTAL=0` |
| rtk | release `v1.0.1`, `rtk-aarch64-unknown-linux-gnu.tar.gz`, sha256 checked against `checksums.txt` |
| Rust | 1.98.1 (the `mise.toml` pin) |
| Runs | 30 per command per tool, 3 warm-ups |
| Results | not yet run |
