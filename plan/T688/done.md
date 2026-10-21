Compare [rtk](https://github.com/rtk-ai/rtk) filters against rtok's rule families: list the filters/commands rtk covers that rtok lacks, and where rtk is broader. Prefer `docs/comparison/rtok-vs-rtk.md` from draft PR #1071 when useful.

Savings for both sides (sourced only; never invent):

| Case | rtok saved | rtk saved | Source |
|---|---|---|---|
| bash bytes cut (author corpus, 5,725 cmds) | no data | 40.4 % (1.1 M of 2.8 M bytes) | `research.md` §2 A/B (T9.2) |
| claimed bash cut | no data | 60–90 % (claim) | `research.md` §2 |
| agentic bill (JetBrains) | no data | +7.6 % to 0 % (worse/neutral) | `research.md` §1, §7 |
| head-to-head harness (`bench/competitors/rtk`) | pending | pending | PR #1071 |

Add `rtok discover`, analogous to `rtk discover`: analyze recorded agent shell calls from rtok's own history/DB and report frequent commands with no filter, ranked by potential token savings, so we know which filters to add next. CLI only; new-command gates (trycmd fence, `surface_parity`, `config_coverage` if config is touched). Docs: `docs/comparison/rtok-vs-rtk.md` coverage table, `CHANGELOG.md`, command help.

Plan (execution):
1. Coverage table: rtk filters from its repo files (cited commit, checked 2026-10-15) against `rules/default.toml` and the `FORMATTER_STEMS` families; dated table in `docs/comparison/rtok-vs-rtk.md`.
2. `rtok discover`: `Store::hook_inputs_since` (Diesel, PostToolUse hook bodies across sessions) -> `measure::discover` (pure ranking: `bash_family` + `filter_kind == "default"` over `Settings::from_config`, ranked by estimated tokens of output the agent read) -> `crate::model::discover` -> `cli/discover.rs`. The `rtok run` wrap strip moves from `guard` to `names.rs` so both reuse it.
3. Tests: fixture-store unit tests (ranking, no-filter detection, wrapped commands), trycmd fence, `surface_parity`, `man.stdout` `v[..]`; CHANGELOG, docs/commands.md, docs ru/uk.
4. Verify: `just check`.
Done when: a dated coverage table (rtk command/filter vs rtok rule family) is in the comparison doc; `rtok discover` prints the ranked gap list from a fixture store and from the live local DB.

Check: unit tests for ranking and "no filter" detection on a fixture DB; trycmd fence for `discover`; coverage table committed; `just check`.

Result: `docs/comparison/rtok-vs-rtk.md` has a dated (2026-10-15) coverage table of all 92 commands in rtk's `src/discover/rules.rs` at commit `5eb0509` (release v1.0.1) against rtok's families: 11 `formatter`, 20 `rule`, 61 `none`; rtok is broader on nine stems. `rtok discover [--since] [--limit] [--json]` reads the stored `PostToolUse` shell bodies (`Store::hook_inputs_since`, Diesel), maps each command with `bash_family` and `filter_kind` over the effective rules (user rules included) and ranks the families with no rule or formatter by output tokens read. It claims no saving. The `rtok run` wrap strip moved from `guard` to `names.rs` and both use it. Tests: `measure::discover` unit tests on strings and on a fixture store, trycmd `discover-json` / `discover-table`, `surface_parity`, `config_coverage`. The follow-up that adds rule families for the top gaps is T662 and later cards.
