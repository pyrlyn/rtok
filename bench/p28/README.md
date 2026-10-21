# Gate P28 live bench (T385.9.3)

`rtok bench --suite p28` runs the P28 compressor (`[plugins.compress.llm]`) against the v0.1
lossless archive pointer. It spends API money, so it runs only with `RTOK_BENCH_LIVE=1`; without
it the table prints zeros and nothing is called.

Two arms, identical except for the compressor switch and the port:

| Arm | Proxy config | Settings | Port | `RTOK_HOME` |
| --- | --- | --- | --- | --- |
| `lossless` | `lossless.toml` | `lossless.json` | 8791 | `/tmp/rtok-p28/lossless` |
| `llm` | `llm.toml` | `llm.json` | 8792 | `/tmp/rtok-p28/llm` |

Both are `proxy.mode = "compress"` with `keep_turns = 2` and `min_tokens = 300`, so the seven
fixture bodies (`tests/fixtures/p28_must_keep.toml`, 376–545 estimated tokens each) are
archived and the first one leaves the live zone while the agent is still reading.

## Run

From the repo root, in three terminals:

```sh
rm -rf /tmp/rtok-p28   # fresh stores: summaries are cached per archive id
RTOK_HOME=/tmp/rtok-p28/lossless RTOK_CONFIG=bench/p28/lossless.toml rtok proxy
RTOK_HOME=/tmp/rtok-p28/llm RTOK_CONFIG=bench/p28/llm.toml rtok proxy
```

```sh
claude --version
RTOK_BENCH_LIVE=1 rtok bench --suite p28 --tasks bench/p28.toml --runs 3
```

`--dry-run` prints the schedule (6 tasks × 2 arms × runs).

Each run writes the bodies to a scratch directory and has `claude -p` read them one per call,
the task's `first` body first, then answer a question whose answer is a must-keep span from the
middle of that body (hidden by the pointer's 8 head and 4 tail lines). `--allowedTools` is
`Read,Bash(rtok expand:*)`, so the lossless arm can recover through `expand` or a second `Read`.

## Reading the table

Per task and arm: mean input, cache and output tokens, mean agent USD (`total_cost_usd`), and
passes. Per arm:

- `agent_usd`: the agent's own spend, summed over all runs.
- `compressor_tokens`, `compressor_usd`: the `internal`-lane usage the arm's proxy recorded
  during the run (a `+` after the USD means some model had no `[stats.prices]` row).
- `usd_per_pass`: `(agent_usd + compressor_usd) / passes`, the Gate P28 number.
- `summaries`, `must_keep`, `expand`: summaries in the arm's store; how many keep every
  fixture span of the body they summarise; how many originals `expand` still returns.

Gate P28 is green when the `llm` arm's `usd_per_pass` is below `lossless` with its pass rate
held, every summary passes `must_keep`, and every original passes `expand`. Record the table,
`claude --version`, the rtok commit and the date as one row in `research.md` §16.

## Caveats

- A summary is stored once per archive id, and the bodies are the same in every run, so the
  compressor is paid in the first run of each body only; later runs see the summary from their
  first request after the body leaves the live zone. That is how the cache works in real use,
  but with `--runs N` the compressor's cost is spread over N runs: record `--runs 1` on fresh
  stores as well, the conservative read.
- `agent_usd` is Claude Code's own estimate; the proxy stores' `rtok stats --price` give the
  same tokens priced at `[stats.prices]`.
- The arms run one after the other on the same tasks; the prompt cache is per arm (different
  request bodies), so order does not share cache reads.
