**T47.1 Rename `rtok agents setup` to `rtok agents install`** · P1, 2/5 · `src/cli.rs`, `tests/agents_install.rs` (renamed), docs, READMEs and tests that name the command; `plugins/{cursor,pi,opencode}/README.md`
Do: the host installer is `rtok agents install <host>`; `setup` stays a clap alias, and the deprecated top-level `rtok setup` points to `rtok agents install`. Every doc, README, help snapshot and test says `agents install` (done.md history keeps the old spelling). `tests/agents_setup.rs` is `tests/agents_install.rs`. The plugin READMEs for Cursor, pi and OpenCode are rebuilt with the new command and their `## Docs` lists.
Check: `cli_trycmd`, `agents_install`, `agent_remove`, `cursor_plugin`, `pi_plugin`, `surface_parity`, `config_coverage`, `host_docs`.
Status: done 2026-09-17 · Model: Claude Code / Fable 5.1
Evidence: `just check` exit 0 (705 passed), which runs every test above.
Deviation: the code rename landed inside another agent's commit `a423aca`, together with three plugin READMEs my rename script had emptied; this commit restores those READMEs and closes the task.
