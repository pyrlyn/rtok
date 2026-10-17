# Shared competitor bench harness

One harness for every "compress command output for an LLM" competitor. The
only variable between two columns of a result is the tool binary.

```
python3 bench/harness/run.py rtk            # rtok vs rtk
python3 bench/harness/run.py rtk --rtok-pr feat/rtk-parity-filters
```

Python 3.10+ and a working `docker` CLI are the only host requirements
(macOS, Linux, Windows; no shell). On macOS set `DOCKER_HOST` to a Colima
socket, or let `run.py` start the `rtok-bench` profile.

## Fairness rules (enforced by the code, not by convention)

* **Same container, same input.** Every tool runs in one image
  (`--cpus 4 --memory 8g --network none`) on the rtok repository checked out
  at `FIXTURE_REF` (pinned SHA, `run.py --fixture-ref`).
* **Real output, captured once.** Each case runs the real command once
  (`capture`), normalized only where the bytes are non-deterministic
  (wall-clock durations become `0.00s`, `RUST_TEST_THREADS=1`). The capture is
  then replayed by a PATH shim (`replay.py`) to every tool, byte-for-byte. A
  tool that runs a *different* argv (`rtk git status` runs
  `git status --porcelain -b`) falls through the shim to the real binary on
  the same fixture state.
* **No caches.** Every invocation gets a fresh empty `HOME`, `XDG_*`,
  `RTOK_HOME`, `RTK_DB_PATH`, `RTK_TEE_DIR`, ... directory.
* **One tokenizer.** tiktoken `o200k_base`, pinned in the image.
* **Pinned versions.** Base image digest, tool release + sha256, rtok git SHA;
  all written to `versions.json`.
* **Determinism is checked.** Each tool runs every case twice from fresh
  state; differing bytes fail the case (`"deterministic": false`).
* **Identical timing schedule.** 3 warmups per tool, then `RUNS` rounds
  (default 30); in each round every tool runs once, in an order shuffled with
  a fixed seed. Latency is median wall time of the replayed command, so it is
  filter overhead, not cargo/git time.

## Adding a competitor

`tools/<slug>/Dockerfile` (`FROM rtokbench/base`) installs a pinned
release, and `tools/<slug>/tool.py` maps a case argv to the tool's argv
(`def argv(case_argv: list[str]) -> list[str] | None`, `None` = the tool has
no mode for that command, reported as `n/a`). Cases live in `cases.py`.
