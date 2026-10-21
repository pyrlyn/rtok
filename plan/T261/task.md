---
status: in progress
priority: P2
complexity: 3
readiness: 95%
agent: Claude Code / claude-opus-5-5
---

# T261. CI takes ~9.5 min on macOS; the webui check recompiles 183 crates every run

Creator request 2026-09-24: find what makes CI and the tests slow, try fixes in a draft PR, do not merge. Measured on #326:
- `check (macos-latest)` is the critical path. Of its ~9.5 min: mise 53 s, cache restore 65 s, fmt 3 s, clippy 46 s, nextest's test-profile build 1 min 47 s, 1733 tests 79 s, then about 39 s of `build-min`, jscpd, oxlint and pytest.
- `just webui-check` takes 2 min 6 s on macOS and 1 min 33 s on ubuntu. `crates/rtok-webui` is excluded from the workspace, so its `target/` is not in `rust-cache`, and 183 crates compile from scratch on every run.
- 78 `tests/*.rs` files mean 78 test binaries to link.

Plan (a draft PR; each change measured with `workflow_dispatch` runs on the branch, warm cache):
1. `rust-cache` also caches `crates/rtok-webui/target`.
2. A `lint` job on ubuntu takes fmt, clippy, `build-min`, jscpd, JS, Python and `webui-check`. The `check` matrix keeps its name and runs only the tests and examples, so macOS stops paying for lint. Trade-off to report: clippy no longer runs on macOS-only `cfg` code.
3. `CARGO_PROFILE_DEV_DEBUG=0` in CI only (smaller test binaries, less linking).
4. Estimate folding `tests/*.rs` into one integration binary (78 links become 1); report it, do not do it here.

Check: warm-cache `workflow_dispatch` runs on the draft PR are green, and a PR comment gives before/after timings per job.

Progress (Cursor / grok 4.7): items 1–3 already on `origin/main` via #332 — do not redo. Draft PR https://github.com/pyrlyn/rtok/pull/411 (`t261-ci-timings`). Two `workflow_dispatch` runs posted timings in a PR comment; both runs **failed** (Check not met — card stays open).

**Blocker (exact errors from the logs):**
1. `lint` — `just … dup` / jscpd: `ERROR: jscpd found too many duplicates (2.1%) over threshold (2.0%)` (`.jscpd.json` `threshold: 2`; 198 clones). Same on both runs. `webui-check` / `publish-dry` and lint’s rust-cache save were skipped because of this.
2. `windows` — `cargo nextest` exit 1. Failures (warm run [36202660499](https://github.com/pyrlyn/rtok/actions/runs/36202660499)): `agents::devin::tests::plugin_manifest_matches_the_installer`; `agents_install::{dry_run_setup_creates_nothing_and_copies_nothing, setup_twice_takes_one_backup_and_says_already_installed, remove_twice_says_no_changes_and_the_second_takes_no_backup, list_reports_installed_modules_per_host}` (5 failed; cold run [36201850046](https://github.com/pyrlyn/rtok/actions/runs/36201850046) also failed `plugins::checkpoint::tests::session_end_on_a_large_transcript_is_bounded`).

`check (ubuntu-latest)` and `check (macos-latest)` were green both times; cold→warm job wall: ubuntu 161 s → 146 s, macOS 362 s → 222 s (see PR #411 comment).

Fold estimate (not folding): **82** `tests/*.rs` bins. macOS log `Finished test profile`: cold **2 m 05 s**, warm **1 m 16 s**. No per-binary `Linking` lines in the CI log. Fold not in this PR (82-file mechanical move; own task).

Progress (Claude Code / claude-opus-5-5, 2026-10-21, local only: this agent may not edit `.github/workflows/` and has no Actions access):
- Both blockers are gone on `main`. `just dup` passes: 408 clones, 1.79 % of lines (threshold 2). The Devin and `agents_install` Windows failures were fixed by #418 (`eaa385ea`, "Devin CI fixes"), and T425 (#759, `e37bd4cf`) replaced the wall-clock bound of `session_end_on_a_large_transcript_is_bounded` with a scaling check. `windows` runs inside `ci.yml`, which `pipeline.yml`'s `gate` needs, so every pull request merged since then has passed it.
- The title's cause is gone: T310.12 (#664, `3f6d69e7`) deleted `crates/rtok-webui`, so `webui-check` and its 183 uncached crates no longer exist, and item 1 has nothing left to cache.
- CI has changed shape since #411. Test binaries are built once per OS (`build-tests`, `cargo nextest archive`) and run in 2 shards (`test`, `windows`); sccache is on; `MACOS_RUNNER` can move macOS to the dedicated Mac. The #411 job timings no longer describe the critical path.
- Item 4, re-measured on this Mac (16 cores, `cargo test --workspace --no-run --timings`, local `line-tables-only`): 129 `tests/*.rs` files (142 test executables in the workspace). Fresh `target/` (the local sccache from `mise.toml` was warm, and it does not cache the link of a test binary): `Finished` in 1 m 14 s; the 129 test units took 400.7 CPU-s (median 3.26 s) over 28 s of wall. After `touch src/lib.rs`: `Finished` in 46 s; the `rtok` lib test unit took 15 s, and the 129 test units took 360 CPU-s (median 2.98 s, smallest 1.18 s) over 25 s of wall. On a 3-core hosted macOS runner, 360 CPU-s is about 120 s, which matches the 76–125 s `Finished test profile` in #411. The fold is parked as I-139; it is not done here.
- Left for the Check: one warm-cache `workflow_dispatch` run of `ci.yml` that is green, plus a PR comment with per-job timings against #411's table. The `save-if: … workflow_dispatch` lines in `ci.yml` (`lint` and `check` are marked "T261 experiment"; `build-tests` and `docs` have the same condition) keep manual runs warm for exactly that. Once the run is recorded, the creator decides whether they stay.
