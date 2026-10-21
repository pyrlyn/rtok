From `research.md` §8 and §9.3: Fable is 39 % of the bill in §9.3, but `config/default.toml` ships prices only for `claude-sonnet-5`, `claude-haiku-4-5`, `gpt-5` and `gpt-5-mini`, so `rtok stats --price` leaves the main workload model unpriced. §2's cost split assumes output = 5 × input "until known".

Done means: a `[stats.prices."claude-fable-5-1"]` row (and any other current Claude 5 model ids the transcripts use) with input, output, cache-write and cache-read rates, each from Anthropic's pricing page with the date checked. §2's assumption is replaced by the real rate, or §8 says why a rate is still missing.

Check: `rtok stats --price` on a fixture with a Fable row prices it; `docs/config.md` lists the row; `just check`.

Result (2026-10-06, Claude Code / claude-opus-5-5): `default_stats_prices()` ships `claude-fable-5-1` (10 / 12.5 / 0.25 / 50), `claude-opus-5-5` (4 / 5 / 0.2 / 20) and `claude-sonnet-5-5` (2 / 2.5 / 0.2 / 10) USD per MTok from https://platform.claude.com/docs/en/about-claude/pricing (checked 2026-10-06), mirrored in `config/default.toml` and `docs/{,ru/,uk/}config.md`. `research.md` §2 and §8: the p_out = 5 × p_in split is the real Fable 5.1 ratio. `tests/stats_price.rs` prices a Fable row from the shipped defaults.
