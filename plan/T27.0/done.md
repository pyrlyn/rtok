**T27.0 the crate exists and the five hosts move onto it** · T26.0 · `crates/rtok-agent-sdk/*`, `Cargo.toml`, `src/setup/*.rs`, `src/proxy/cli.rs`, `src/cli.rs`, `.jscpd.json`
Do: the crate carries `Apply` (the `[setup]` flags: `dry_run`, `backup`, `yes`), `NO_CHANGES` as
both the report and the write gate, `backup`, `read_json` / `write_json` / `write`, `register_mcp`
/ `unregister_mcp`, `accepted` (dialoguer moves with it), and `PluginLink` — the offer/link/unlink
`plugins/cursor` and `plugins/pi` both spell today. Three dependencies, none of them C, the line
T23.0 drew for the plugin SDK. Claude, Cursor, Codex, OpenCode, pi, `proxy::cli` and `migrate` all
route through it; no report string changes, because the host integration tests assert them.
Check: `cargo test --workspace` green with the `agent setup`/`agent remove` integration tests
(`tests/cursor_plugin.rs`, `tests/pi_plugin.rs`, `tests/agent_remove.rs`) unmodified; `just dup`
does not regress; `rtok agent setup <host> --dry-run` prints the same lines as before for all five.
Complexity: 3/5
Status: done 2026-09-09 · Model: GLM-5.3 (subagent)
Check result: green, verified in a detached worktree. The three integration test files are
byte-identical (`git diff <base>..HEAD --stat -- tests/` empty) and pass (7/6/4). Dry-run strings:
base binary and new binary built, `agent setup <host> --dry-run` run for all five hosts in a
sandboxed HOME, normalized and diffed — byte-identical. `just dup` improved: 36 clones / 1.42 %
vs 49 / 2.17 % at base. `cargo test --workspace` had one failure, the pre-existing `graph_truth`
red that T8.19 (same round) then fixed; the full `just check` on main after all three landings is
green. Gate P27 holds: no production code under `src/setup/` writes a host file, copies a backup
or symlinks — only test-fixture `fs::*` remains.
Deviations: the bulk of the work was authored by an earlier session and left uncommitted (snapshotted
to main as `5975877` "T27.0 (wip)"); this round completed and verified it — restored the three report
strings the WIP had let drift (pi's dry-run source paren, cursor's `+ plugin` trailing label, cursor's
declined-offer/question paren) and pinned all three in the SDK's unit tests, dropped `dialoguer` from
the root manifest (it moved with `accepted`), and fmt. One inherited behaviour change kept: `migrate`
no longer rewrites/backs-up when its diff is empty (the SDK's `NO_CHANGES` write gate) — no report
string changed and no test asserted the old churn. 16 files, the task's own list in plan.md.
