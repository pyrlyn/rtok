Ivan, 2026-09-30: emoji and colour on by default, with a config toggle, for human-facing output only. Agent-facing and JSON output stay clean. Colour is off outside a TTY and with `NO_COLOR`. PR: https://github.com/pyrlyn/rtok/pull/532.

Plan:
1. One style table (`src/ui/style.rs`): each line kind (success, info, warning, error) picks its emoji and colour together, and clap's `--help` / error styles live in the same place.
2. Config: `[ui] emoji = true` and `[ui] color = true` in `config/default.toml` and `docs/config.md`. `color = false` turns colour off process-wide (owo-colors override), so `render.rs` diffs, state words and log levels follow the same key. Re-bless the trycmd config snapshots.
3. Where it applies: human-facing lines only (status, summaries, `doctor`, `agents` output, `--help`). It never applies to what agents read: `rtok hook` stdout, MCP responses, the proxy, filtered command output from `rtok run` / `rtok filter`, `--json`, or anything written to a pipe or a file.
4. Gates: an emoji needs `[ui] emoji` and a terminal on that stream. Colour needs `[ui] color` and owo-colors' `if_supports_color` answer for that stream: a TTY, `NO_COLOR` unset, `TERM` not `dumb`, or `CLICOLOR_FORCE` / `FORCE_COLOR` set.
5. Deliver as a PR; do not merge it.

Dependencies: `owo-colors` 4 (`supports-colors`) is already a dependency (T20.2); no new crate. Independent of T314.

Check: `tests/ui_style.rs` covers each line kind with emoji and colour on and off; `rtok hook`, MCP and `--json` output contain no ANSI escapes or emoji with both keys on and `CLICOLOR_FORCE=1`; piped output and `NO_COLOR=1` output have no colour; `just check`.

Result: merged in #532 (`7c09b8d3`). `src/ui/style.rs` owns one table: success (✅ green), status (💡 cyan), warning (⚠️ yellow), error (❌ red), plus clap `Styles` for `--help` and clap errors. Config `[ui] emoji = true` and `[ui] color = true` (env `RTOK_UI_EMOJI` / `RTOK_UI_COLOR` through the existing layer); `color = false` sets the owo-colors override, so `render.rs` diffs, state words and log levels follow it. Emoji need the key and a terminal on that stream; colour needs the key and owo-colors' per-stream check (`NO_COLOR`, `TERM=dumb`, `CLICOLOR_FORCE` / `FORCE_COLOR`). Hook and MCP JSON, filtered command output, `--json`, agent messages and piped output are byte-identical; the `Error: {e:?}` line keeps std's bytes. No new dependency. `--help` colour is clap's own decision, since argv is parsed before config loads.

Check result: `src/ui/style.rs` unit tests (emoji key × tty matrix, prefix shape, bare text off a terminal, `color = false` beating a forced terminal); `tests/ui_style.rs` on the real binary through pipes (plain `ok` line, `NO_COLOR` no-op, std's error bytes, `CLICOLOR_FORCE` paints without emoji, `RTOK_UI_COLOR=false` and `[ui] color = false` beat `CLICOLOR_FORCE`); trycmd `config init` / `config show` / `report` gain the two `ui.*` keys; `just check` green locally (2064 tests) and PR CI green.

Status: done 2026-09-30 (#532)
Model: Grok Bot
