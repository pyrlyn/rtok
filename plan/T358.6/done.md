Scope: what T358.1 and T358.2 left out of the screen. `--by agent|model` (config `[agents.usage] by`); for `--source rtok|both` the saved tokens and saved estimate columns and the `rtok saved` summary line from the `measurements` ledger (T358 "`rtok` — what passed through rtok"); in `both`, the agents that appear only in the store next to the logs' agents, with logged tokens 0; the JSON `skipped` field and the stderr line for a host whose files exist but cannot be parsed.

Check: the T358 Check items for `--by`, the saved columns and `both` coverage on fixture homes and a fixture store; `just check`.

Execution:

1. Stack on T358.2 (#660). `Store::measurement_saved_by_host(since, until)` sums the ledger's `est_before - est_after` per session in the window through Diesel and maps sessions to hosts (the host map is now one helper shared with `usage_slices`); `measurement_totals` has no window or session, so it could not serve.
2. `agents::usage::report` takes `by`, fills `saved` per agent and in the totals (valued at the average input price that host's requests paid), lists store-only agents in `both`, and carries `skipped`; `measure::usage::read` and `codex::requests` report the first unreadable file per host.
3. Unit tests, a trycmd case for `--by model`, goldens, `just check`.

Result (2026-10-03, Claude Code / sonnet-5): `--by model` swaps the middle table for model rows (raw ids, dearest first; JSON `models`). For `--source rtok` and `both` the agent table gains `Saved tokens` and `Saved est.`, the summary gains `rtok saved N tokens (≈ $X)`, and JSON gains `saved_tokens` and `saved_usd` on each agent and in `totals`; the net saving counts an `expand` row as a cost, so it can go negative, and `saved_usd` is `null` when none of that host's models has a price. `both` also lists agents that only passed through rtok (no logged tokens, coverage `-`). A Claude Code or Codex dir whose files are unreadable or hold no JSON line is named once on stderr (`skipped claude: unknown format in <path>`) and in JSON `skipped`, and counts nowhere. Config `[agents.usage] by` and `--by` have their `default.toml` and `docs/config.md` rows. Checked: unit tests for the net ledger per agent and total, the window, the price rate and a negative `expand`; `--by model`; the store-only agent in `both`; `skipped` and its host filter; the stable JSON field names; a trycmd golden for `--by model --daily`. Not done: the saved estimate uses one average input rate per host (not per model); the `measurements` ledger has no host column, so a session with no host row is in the total only.

Status: done 2026-10-03
Model: Claude Code / claude-sonnet-5
