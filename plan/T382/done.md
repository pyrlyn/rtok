Ivan, 2026-10-04: `rtok agents list` and `rtok web` `#/hosts` must show which version of the rtok plugin each host has installed. Today the `plugin` row says only `✓ plugin  installed`, so a stale plugin (older than the binary) is invisible without opening the host's own records (for Claude `~/.claude/plugins/installed_plugins.json`: `version`, `installPath`, `gitCommitSha`).

Depends on T279: it defines where the installed version comes from (the installed copy's `.rtok-plugin-version`, then the install receipt, then the host record). Reuse that lookup; do not add a second one.

Done means:

- `agents::list`: the `plugin` row of every host that has a plugin carries the installed version and its source, e.g. `✓ plugin  installed 0.15.1 (marketplace)`. When the version differs from the running binary it says so: `installed 0.14.0 (marketplace), rtok is 0.15.1 — rtok agents update claude`. An install with no version anywhere shows `installed (legacy, no version)`, matching T279's `agents info` wording. Hosts with `− plugin not supported` are unchanged.
- `rtok agents list --json` gains `plugin_version` and `plugin_source` fields; absent rather than empty when unknown.
- Web: the Hosts page reads `agents::list` verbatim (`hosts_page_text` in `src/web/model.rs`), so the version arrives with the text; `parseHosts` in `web/src/pages/text.ts` keeps it in the module row's state and `Hosts.tsx` shows it, with the outdated case visibly marked. The TUI Hosts page shows the same text.
- Tests: an `agents::list` unit test over a fake Claude home with an installed plugin at the binary's version, at an older one, and with no version; a `parseHosts` test for the three row shapes; trycmd/snapshot files that print the `plugin` row re-blessed.

Check: the three `agents::list` cases and the `parseHosts` test pass; `rtok agents list` on this machine prints `installed 0.15.1` for Claude Code; `#/hosts` in `rtok web` shows the same; `just check`.

Execution: the per-variant body of `agents outdated` (`install_probe` + `read_installed`, T279/T279.1) became one shared `installed_plugin` in `src/agents/outdated.rs`; `agents::block` appends `plugin_status`'s note to the `plugin` module row, `web/model.rs::agent_row` adds `plugin_version`/`plugin_source`, `parseHosts` keeps the row value and `moduleState` in `Hosts.tsx` marks the outdated case. No second lookup.

Result (2026-10-08, Claude Code / claude-sonnet-5-5): `rtok agents list` and `agents info` print `✓ plugin  installed 0.15.1 (github)`; older: `installed 0.14.0 (github), rtok is 0.15.1 — rtok agents update claude`; newer: `, rtok is 0.15.1` without the hint; no version anywhere: `installed (legacy, no version)`. `--json` rows carry `plugin_version`/`plugin_source`, absent when no plugin is installed or no version is recorded. The Hosts page shows the note and marks an older plugin with a warn `outdated` pill (a newer one `newer`). The live Hosts text leads every module row with a state mark (`✓`, `✗`, `−`) that `parseHosts` did not skip (fixtures had none), so `parseHosts` now skips it and the fixture carries marks. Tests: `tests/agents_plugin_version.rs` (fake Claude home: current, older, newer, no version, no plugin, text and JSON), `parseHosts` and `moduleState` cases. Docs: `docs/plugin-versions.md`, `docs/commands.md` and the `ru`/`uk` copies. Checked: `just check` (2792 tests passed, oxlint/oxfmt clean), `npm run typecheck`, the Hosts stories (21 passed); `rtok agents list` on this machine prints `installed 0.15.1 (github)` for Claude Code.

Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
