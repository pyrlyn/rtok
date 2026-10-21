Found 2026-09-22 in the host-plugins pass: `tests/agents_install.rs:20-71` `hosts()` covers 14 of `HOSTS`' 15 ids — `omp` has no row anywhere, so its install idempotency, one-backup and remove-keeps-foreign guarantees are unguarded at the integration level (exactly where T196-class bugs live), and `tests/common/agents.rs:118-140` `write_cfg` seeds no `.omp/agent`/`[setup.omp]` to support one. `tests/agents_real_config.rs:31-60` additionally omits kilo, grok, copilot and aider (all with real config files to seed). Separately `plugins/pi/tests/load.test.ts:16-33` probes `pi` with no PATHEXT variants, so on Windows `piPackage()` is null and the loader test — the one proving pi accepts the linked extension (T48.1) — skips silently.

Plan: add `[setup.omp]` keys + the `.omp/agent` fixture to `write_cfg`, an `omp` row to `hosts()`, the four file-owning hosts to `agents_real_config.rs::HOSTS`; probe `pi`/`pi.cmd`/`pi.exe`/`pi.ps1` in the loader test and warn visibly on a skip.

Check: `cargo nextest run --test agents_install --test agent_remove` shows omp in `setup_twice_takes_one_backup_and_says_already_installed` and `remove_twice_says_no_changes…`; `ci_hides_what_this_machine_really_has` iterates the extended list; on Windows with pi installed the loader test runs rather than skips; `just check` green.

Result: `tests/agents_install.rs` `hosts()` gains an `omp` row (`--yes`, `.omp/agent/mcp.json`), and `write_cfg` seeds `.omp/agent` + `[setup.omp]`. `agents_real_config.rs` adds kilo, grok, copilot and aider, plus gemini, codewhale, mimo and omp, which `HOSTS` gained since the card. No installer bug surfaced. `plugins/pi/tests/load.test.ts` probes `pi`/`pi.cmd`/`pi.exe`/`pi.ps1` and `console.warn`s on a skip.

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
