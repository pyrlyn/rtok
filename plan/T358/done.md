Ivan, 2026-10-02: one terminal screen with token usage and estimated cost across all coding agents, modelled on `npx ccusage` (screenshot: [`docs/assets/t358-agents-usage-ccusage.png`](docs/assets/t358-agents-usage-ccusage.png)). Two data sources: what the agents themselves logged on disk, and what rtok recorded passing through it. No product code in this card: it is the spec; split when claiming (below).

Today, nothing answers "how much did all my agents spend": `rtok stats` reads Claude Code transcripts (`[stats] transcripts_dir`) and Codex sessions (`measure::codex`, T49.2) as two `api` rows over a `--since` window, and `rtok stats --price` (T49.1) prices only the proxy `usage` rows by model; there is no per-agent, per-day or per-month view, no other host's logs, and no count of unpriced models outside that table. `rtok agents sessions` (T25.2) shows tokens per live session of this project, not totals. `rtok report` (D24) renders the operator model and has no usage-by-agent section.

#### What the screen shows (default text output)

Illustrative: the numbers are the screenshot's, not a measurement.

```text
$ rtok agents usage
rtok agents usage — logs from 8 agents, through 2026-10-02 (Europe/Kyiv)

  63.76B tokens
  $52,020.90 estimated cost
  10,137 sessions
  220 daily rows

! Cost is incomplete: 15 models have no price in [stats.prices], so their tokens are not in
  the estimate. `rtok agents usage --unpriced` lists them.

Agent               Tokens   Estimated cost
Droid               26.52B       $30,571.46
Codex               24.21B       $13,249.69
Claude Code         12.69B        $7,978.15
OpenCode           155.45M          $142.04
GitHub Copilot CLI 121.49M           $63.69
Gemini CLI          20.04M            $8.97
Grok                43.43M            $6.90
ZCode                22.1K            $0.00

Monthly totals
Month               Tokens   Estimated cost
Feb 2026             2.56B          $888.02
Mar 2026             9.88B        $4,682.41
...
```

1. **Header:** the source (`logs from N agents`, `through rtok`, or both), the last day covered and the time zone used for day and month boundaries.
2. **Summary lines:** total tokens; estimated cost (USD); sessions (distinct session ids across agents); daily rows (distinct `(agent, day)` pairs with usage, the same count ccusage calls "daily rows").
3. **Unpriced warning:** printed only when at least one model has no price: the number of such models (as in the screenshot's "no pricing for 15 models"), never a guessed price. `--unpriced` prints the model ids with their agent and tokens.
4. **Per-agent table:** agent (display name), tokens, estimated cost; sorted by cost descending, then tokens. An agent whose every model is unpriced shows `-` for cost, not `$0.00`; `$0.00` means priced and free or rounded.
5. **Monthly totals:** month, tokens, estimated cost, oldest first. `--daily` swaps it for day rows (`2026-10-02`), `--monthly` is the default view; `--by agent` (default) and `--by model` change the grouping of the middle table.

Numbers use the same short units in text (`22.1K`, `155.45M`, `26.52B`, decimal SI, two decimals above 1K) and exact integers in JSON. Tokens = input + cache write + cache read + output (+ reasoning where the host logs it separately); the four legs stay separate in JSON.

#### Two modes

`--source logs|rtok|both` (config `[agents.usage] source`, default `logs`).

1. **`logs` — the agents' own records (like ccusage).** Read each host's local session files on the fly; never written to the store, so a re-read is idempotent (the T49.2 rule). One reader per host in `src/measure/usage/<host>.rs`, all returning one row type `(host, session, model, ts, input, cache_write, cache_read, output, reasoning)`. Claude Code reuses `measure::jsonl` (`message.usage`, dedup by message id + request id, as `stats::collect` already does) and Codex reuses `measure::codex` (`last_token_usage` per `token_count` line). The others start from ccusage's documented locations (https://ccusage.com/guide/, fetched 2026-10-02) and each is verified against the host's own files before it lands:

   | Host | Default location (env override) |
   | --- | --- |
   | Claude Code | `~/.claude/projects/`, `~/.config/claude/projects/` (`CLAUDE_CONFIG_DIR`) |
   | Codex | `~/.codex/sessions`, `~/.codex/archived_sessions` (`CODEX_HOME`); `sessions/` wins over an archived copy |
   | Droid | `~/.factory/sessions` (`DROID_SESSIONS_DIR`) |
   | OpenCode | `${XDG_DATA_HOME:-~/.local/share}/opencode` (`OPENCODE_DATA_DIR`) |
   | Kilo | `~/.local/share/kilo` (`KILO_DATA_DIR`) |
   | Copilot CLI | `~/.copilot/session-state/*/events.jsonl`, `~/.copilot/otel/**/*.jsonl` (`COPILOT_HOME`) |
   | Gemini CLI | `~/.gemini/tmp` (`GEMINI_DATA_DIR`) |
   | Grok | `~/.grok` (`GROK_HOME`) |
   | ZCode | `~/.zcode` (`ZCODE_HOME`) |
   | Kimi | `~/.kimi`, `~/.kimi-code` (`KIMI_DATA_DIR`) |
   | pi | `~/.pi/agent/sessions` (`PI_AGENT_DIR`) |
   | Antigravity | `~/.gemini/antigravity*`, `~/.config/antigravity` (`ANTIGRAVITY_DATA_DIR`) |

   Every path is also a config key (`[agents.usage.dirs] <host> = [...]`, D12); an env override wins over the default and loses to the config file, like every other key. A host whose files are missing is skipped silently; a host whose files exist but cannot be parsed is named once on stderr (`skipped grok: unknown format in ~/.grok/...`) and counts nowhere. The `measure::codex` header (2026-09-17) says OpenCode's `opencode.db` and Copilot CLI's `data.db` carry no token counts: the readers use the files ccusage reads (OpenCode's message storage, Copilot's `events.jsonl`/OTel export), and if a host really has no counts it is listed as `unsupported`, never estimated. Hosts outside `agents::HOSTS` (Droid) still get a reader: this is about usage on the machine, not about what rtok installs into. D6 holds: rtok reads the files itself; it never spawns or imports ccusage or any other tool.

2. **`rtok` — what passed through rtok.** Read only the store: proxy `usage` rows (input, cache write, cache read, output, model, `api`) grouped by host through `usage.session → sessions.host_id` (rows with no session host go under `unattributed (<api>)`), plus the `measurements` ledger for what rtok removed (`est_before - est_after`, `expand` counted as a cost, as `ReportSavings::saved` does, T207). Two extra columns, **saved tokens** and **saved estimate** (saved tokens priced at that host's input price, labelled as an estimate), and one extra summary line: `rtok saved 1.23B tokens (≈ $812.40)`. Uses the existing reads (`Store::usage_by_model`, `measurement_totals`); new reads go through Diesel, grouped in SQL (no raw SQL, no second recorder, D19).

3. **`both`:** the per-agent table gets `logs tokens`, `through rtok`, and `coverage` (through rtok ÷ logs), so it is visible which agents bypass the proxy. Costs come from the logs side; saved columns from the rtok side.

#### Flags (each one a config key under `[agents.usage]`, D12)

| Flag | Key | Default | Meaning |
| --- | --- | --- | --- |
| `--source logs\|rtok\|both` | `source` | `logs` | Data source, above. |
| `--host <ids>` | `hosts` | all | Comma-separated host ids (`claude,codex,droid`); unknown ids are an error listing the known ones. Named `--host` like `agents info <host>`; if T348 settles on `--agent` for hosts, this follows. |
| `--since <date\|dur>` / `--until <date>` | `since`, `until` | all time | `2026-09-01` or a duration (`30d`, `24h`, `stats::parse_since`); dates are whole days in `--tz`. |
| `--daily` / `--monthly` | `period` | `monthly` | Bottom table: day rows or month rows. |
| `--by agent\|model` | `by` | `agent` | Middle table grouping. |
| `--tz <IANA>` | `tz` | system zone | Time zone for day/month boundaries and the header. |
| `--unpriced` | — | off | List models without a price instead of the tables. |
| `--json` | — | off | One JSON document, below. |

#### Models without a price

Prices come only from `[stats.prices."<model>"]` (T49.1); nothing is fetched from the network and nothing is guessed. Model ids are normalised before lookup (provider prefix such as `anthropic/` or `openai/` stripped, a trailing date suffix such as `-20260901` dropped) and the raw id is kept in JSON. A model with no row: its tokens count in every token total, its cost is `null`, it is counted once per model id in `unpriced_models`, and the warning line names the count (the tokens left out are in `--unpriced` and JSON). Extending the shipped price table (or importing one) is a separate decision, not this task.

#### Time zone

Usage timestamps are stored and emitted in UTC (unix seconds and RFC 3339 `Z` in JSON). Day and month buckets are cut in `--tz` (default: the system zone; `TZ` respected), DST-aware, so a session that crosses midnight splits by each request's own timestamp. The header and JSON `tz` field name the zone used. Parsing IANA zones needs a time-zone crate (`jiff`, one-line reason in `Cargo.toml`: IANA zones with DST for day/month buckets); `chrono`/`time` are not in the tree today.

#### `--json`

```json
{
  "source": "logs",
  "tz": "Europe/Kyiv",
  "through": "2026-10-02",
  "totals": {"tokens": 63760000000, "input": 0, "cache_write": 0, "cache_read": 0,
             "output": 0, "cost_usd": 52020.90, "sessions": 10137, "daily_rows": 220},
  "unpriced_models": 15,
  "unpriced": [{"model": "<model id>", "host": "zcode", "tokens": 22100}],
  "agents": [{"host": "droid", "name": "Droid", "tokens": 26520000000, "cost_usd": 30571.46}],
  "periods": [{"period": "2026-02", "tokens": 2560000000, "cost_usd": 888.02}],
  "skipped": [{"host": "grok", "reason": "unknown format", "path": "~/.grok/..."}]
}
```

With `--source rtok|both` each agent and the totals also carry `saved_tokens` and `saved_usd`, and `both` adds `through_rtok_tokens` and `coverage`. Field names are stable; a golden test pins them.

#### Relationship to existing commands

- `rtok agents list` / `info` say what is installed; `rtok agents sessions` says what is running now in this project; `rtok agents usage` says what has been spent, over time, on the whole machine. No overlap in rows.
- `rtok stats` stays the measurement report (D3) and keeps its `api`/`--price` sections; `agents usage` reuses its readers (`measure::jsonl`, `measure::codex`), `row_cost` and `[stats.prices]` instead of copying them (no duplicated logic). `stats --price` keeps working unchanged.
- D23/D27: the same rows become a Usage page on `rtok web` and `rtok tui` (T358.5), built from one model function both surfaces and the CLI call.

#### Split when claiming (≤300 LOC, ≤10 files each)

- **T358.1** `--source rtok`: CLI skeleton, `[agents.usage]` config section, store reads by host/day/month, summary + both tables + `--json`, unpriced count, `--tz`.
- **T358.2** `--source logs` for Claude Code and Codex, reusing `measure::jsonl`/`measure::codex`; `--source both`.
- **T358.3** Readers for Droid, OpenCode, Kilo, Copilot CLI, Gemini CLI (one per PR if over budget), each verified on real files and pinned by a fixture.
- **T358.4** Readers for Grok, ZCode, Kimi, pi, Antigravity; `unsupported` hosts listed in `docs/agents.md`.
- **T358.5** The Usage page on `rtok web` and `rtok tui` (D23/D27).

Check: every item below passes.

- Fixture homes per host (no real agents in tests): a fixed set of session files gives exact totals, per-agent and per-month numbers in text and JSON goldens (`trycmd`); re-running gives the same output.
- Unpriced: a fixture with one priced and two unpriced models prints the warning with `2 models`, costs only the priced one, and `--unpriced` lists the two.
- Time zone: one request at 23:30 UTC on 2026-09-30 lands in October with `--tz Europe/Kyiv` and in September with `--tz UTC`; a DST change day buckets correctly.
- `--source rtok` on a store fixture matches `rtok stats --price` totals for the same rows; `both` coverage is through-rtok ÷ logs.
- `rtok config validate` accepts every new key; each has its `default.toml` row and `docs/config.md` row; `just check` green.
- The screenshot's layout (summary, warning, per-agent table, monthly totals) is what `rtok agents usage` prints for the fixture.

Result: shipped in six subtasks, all in this file: T358.1 `--source rtok`, the command and `[agents.usage]` (#656); T358.2 `--source logs|both` for Claude Code and Codex (#660); T358.6 `--by`, saved columns and `skipped` (#663); T358.5 the Usage page on `rtok web` and `rtok tui` (#667); T358.3 the OpenCode, Kilo, Copilot CLI and Gemini CLI readers with `[agents.usage.dirs]` (#672); T358.4 the pi and Kimi readers, with Grok, ZCode and Antigravity marked `unsupported` (#678). Checking the readers against real files is T396.

Status: done 2026-10-08
Model: Claude Code / claude-opus-5-5
