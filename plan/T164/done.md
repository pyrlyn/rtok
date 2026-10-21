Assigned as T162, but that id was already taken by a concurrent session's task before this one registered — max open id on `origin/main` was T163, so this landed as T164 instead.

pi, opencode, kilo, zcode and cursor have no GitHub/subdir install path in their own docs, so
they stay on the local-link `PluginLink` mechanism (`src/agents/plugin.rs`, `HostPlugin`) —
unlike claude/codex/opencode's GitHub-store plugins (T139), they previously only offered under
`Support::Flag("--yes")`. `PluginLink::run()` also treated any non-dangling link as "installed
forever", so a symlink left pointing at an older ketch store version, or an owned copy from an
older build, was never refreshed. `HostPlugin` gains `default_install: bool` (true for the five
local-link hosts, false for omp, which the task explicitly left out); `offer()` sets `apply.yes
= true` when set, unless removing. `PluginLink::run()` now distinguishes up-to-date (symlink
target or file/dir content matches this build's source exactly) from stale (ours, but pointing
at a different version or a dangling target — dropped and relinked) from foreign (not ours —
never touched, offer message unchanged regardless of `--yes`); the foreign check runs before
the accept/decline question so a forced auto-accept still can't overwrite someone else's
directory. `remove` only ever unlinks ours. Every path is fail-open: a write error becomes an
informational string in `offer()`'s `Result::Err` arm rather than aborting the rest of `agents
install`. ZCode previously built its plugin link ad hoc outside `HostPlugin`; migrated it onto
the shared `PLUGIN: HostPlugin` static so `default_install` and the up-to-date/stale/foreign
logic apply to it too — `offer_plugin`, `plugin_dirs`, `plugin_serves` and `installed` now all
go through `PLUGIN`. `support()` for cursor/kilo/opencode/pi/zcode's `plugin` module moved from
`Flag("--yes")` to `Yes`; their READMEs, the bundled `plugins/<host>/README.md` prose, and
`docs/agents.md` (`RTOK_BLESS=1`) follow. `tests/trycmd/doctor.toml` and
`tests/trycmd/report-md.toml` lost the `(--yes)` suffix for those five hosts only — omp keeps
it. `tests/agent_remove.rs`'s `zcode_remove_keeps_foreign_events_and_servers` assumed `install
zcode` without `--yes` left the config-file's own hooks/mcp entries in place (the pre-T162
default); now the plugin links on that same call and, per ZCode's "linked plugin is the only
call path" design, the config-file entries are never written at all — rewritten around the
plugin link and `plugins.dirs` entry instead.

Check: `cargo nextest -p rtok-agent-sdk --lib` for the up-to-date/stale/foreign/dry-run/remove
table; `cargo nextest -E 'test(/agents::/)'` for `default_install_is_exactly_the_local_link_only_hosts`
and every host's own tests; `tests/agent_remove.rs`, `tests/agents_install.rs`,
`tests/opencode_plugin.rs`; `RTOK_BLESS=1` on `tests/agents_doc.rs`; `TRYCMD=overwrite` on
`tests/cli_trycmd.rs`; `tests/host_docs.rs`; `just check`.

Status: done 2026-09-22
Check result: `cargo nextest -p rtok-agent-sdk --lib` 24 passed (3 new: relink on a stale
version target, relink on a stale owned copy, never install over a foreign directory).
`cargo nextest --lib -E 'test(/agents::/)'` 125 passed, including
`default_install_is_exactly_the_local_link_only_hosts` (table over all 6 hosts) and
`declared_host_plugins_have_distinct_hosts`. `cargo nextest --test cli_trycmd` 45 passed after
`TRYCMD=overwrite` (`doctor.toml`/`report-md.toml` diff was exactly the 8 `(--yes)` removals —
cursor/opencode/kilo(×2 each, cli+desktop)/pi/zcode — omp's untouched). `tests/agents_doc.rs`
and `tests/host_docs.rs` pass after `RTOK_BLESS=1`. `tests/agent_remove.rs`,
`tests/agents_install.rs`, `tests/opencode_plugin.rs` pass (17/17, 1/1, 4/4 respectively).
Full `just check` (fmt-check, both clippy passes, `cargo nextest --workspace` 1221 passed 2
slow, `build-min`, `dup`, `js`) is green, run in two parts because of an unrelated, pre-existing,
environment-only failure in `windsurf_keeps_the_real_mcp_config_json` (`tests/agents_real_config.rs`,
T78): it copies this machine's real `~/.codeium/windsurf/mcp_config.json`, which currently holds
`{"mcpServers":{}}` with nothing foreign to compare, so the test's own self-check panics
("nothing foreign in the seeded configs, so this proves nothing") — the file is untouched by
this diff, the test does not run at all in CI (no such file there, it hits the earlier skip
branch), and excluding just that one test with `-E 'not test(windsurf_keeps_the_real_mcp_config_json)'`
left the remaining 1221 passing.
Deviations: the diff is 24 files (over the ≤10-file guide) — the shared decision in
`rtok-agent-sdk`/`plugin.rs` and its five host call sites, their READMEs, the regenerated docs
and trycmd goldens, and three test files, do not split into per-host PRs without breaking the
shared `default_install_is_exactly_the_local_link_only_hosts` table test and leaving hosts on
inconsistent behavior in between; kept as one PR. ZCode's Windows README paragraph is rewritten
to state honestly that there is currently no flag to keep the plugin off there (the plugin
cannot run under the POSIX launchers), which is a slightly wider disclosure than the task asked
for but was needed once `--yes` stopped being a way to skip it.
Model: Claude Code / sonnet-5
