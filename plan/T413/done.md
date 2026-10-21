Creator request 2026-10-05: add installers for popular coding agents missing from `src/agents/` (22 hosts on `9d6557c7`). One sub-task per host, one PR each. The list below is a lead, not evidence: every fact a sub-task relies on (binary, config paths, MCP entry format, hook events and payloads, plugin or extension API) is first written into `research.md` with its primary source (vendor docs or the repository's own files, URL plus date checked).

Shared shape for every sub-task, per `architecture.md` "New host" and D21:
1. Research: a `research.md` row for the host with the sources above; if a field is not documented, it stays out of the installer (never guessed).
2. `src/agents/<host>/mod.rs` implementing `Agent` (variants, detection, files, installed modules, apply) and `README.md` with the module table and `## Docs`; registered in `HOSTS` and `host()`; `[setup.<host>]` paths in `config/default.toml`, `src/config/mod.rs`, `docs/config.md`.
3. Reuse first: a host that forks or mirrors an existing one (named per card) shares that host's code through a helper, never a copy. Host configs: check only rtok's entry, keep the rest byte-for-byte; backups and `--dry-run` from `rtok-agent-sdk`.
4. `docs/agents.md` row; `agents_install` matrix row (install twice / remove twice / list) against fake homes, never the real agent.
5. Per-host check: the targeted tests above, `agents_doc` and `host_docs` green, `just check` green; one manual `rtok agents install <host> --dry-run` on this machine if the host is installed here, otherwise say "not installed here" in `done.md`.

Check: every sub-task below is closed in `done.md` with its per-host check.

Done: all 15 sub-tasks are closed below. Thirteen added installers: Roo Code, Qwen Code, Factory Droid, Kiro (CLI and IDE), Amp, Goose, Continue, Augment (Auggie CLI), JetBrains Junie, Crush, Warp, OpenHands and DeepSeek Reasonix each have a `src/agents/<host>/` directory on main. Two closed without code: Amazon Q Developer CLI went into Kiro (T413.10), and Trae documents no user-level MCP file path (T413.13). The Trae installer is in `roadmap.md` "Agent hosts" and waits for a documented path. Since T663 the hosts share `agent_identity!`, `mcp_only_file!` and `assert_mcp_only_apply_idempotent`, which keeps `just dup` under its threshold.

Status: done 2026-10-21
Model: Claude Code / claude-opus-5-5
