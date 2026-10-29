# rtok-kotlin roadmap

Work the creator approved that is not in `plan/` yet. Unapproved ideas live in [ideas.md](ideas.md);
decisions and history live in [port-plan.md](port-plan.md).

## Port

- hooks: the 29 dispatch tests that wait on worktree, otel and MCP wiring.
- graph: about 275 Rust tests, including `grep_symbol` through tree-sitter and the CLI wiring.
- CLI tests per subcommand (Kotest spec plus an e2e case): stats, expand, filter, run, config show/get, plugins `--json`, report, completions, man.
- Proxy, MCP server, agents install/uninstall/doctor, and the worktree, task, batch, demon and bench commands: ported tests and `docs/<feature>.md` for each (the phase-exit rule in port-plan.md).
- Skill sync for all hosts.
- `rtok doctor`.
- Worktree removal in junk.
- continue and goose hosts (a YAML library decision is needed first).
- Windows `mingwX64` target (absent).

## Linux hardening (P3)

- Free the spawn attributes after a failed `posix_spawnattr_init`.
- Retry on EINTR.
- Trash race and UTC `DeletionDate`.
- `/tmp` fallback.
- Seed tie on equal mtimes.
