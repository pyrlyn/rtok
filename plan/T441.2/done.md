Second subtask of T441 (task adapters): the domain types every adapter, the CLI and the MCP tools share, plus the `[tasks]` config section, with unit tests.

Check: `TaskId` parse/print round-trip, ordering and invalid input; `Status` round-trip; the config section in `default.toml`, `docs/config.md` (ru, uk) and the validator; `just check` green.

Result: new `src/tasks/mod.rs` with `TaskId` (ASCII-letter prefix of 1–8, dotted path of depth ≤ 2, numbers from 1 without leading zeros, case-insensitive in, upper case out, numeric ordering, serialized as a string), `Status` (`open`, `in-progress`, `done`, `closed`; also takes the hosts' `pending`/`in_progress`/`completed`), `Task`, `NewTask`, `ExternalRef`, `check_prefix` and `default_prefix` (first letter of the project name). `[tasks]` in `src/config/mod.rs`: `adapter` (`disk` default), `prefix`, `[tasks.disk] dir`, `[tasks.github] repo, project`, `[tasks.gitlab] url, project`; `config validate`/`set` reject an unknown adapter and a prefix ids could not carry through the same `check_prefix`. Creator decisions recorded in the T441 card §12: `Closed` is its own status; this repo's prefix is `A`, written by T441.5's `rtok task init` rather than now, because installed builds before T441.2 reject an unknown `[tasks]` table in `.rtok.toml`. trycmd snapshots for `config init`, `config show` and `report --md` gained the seven keys.

Status: done 2026-10-07
Model: Claude Code / claude-opus-5-5
