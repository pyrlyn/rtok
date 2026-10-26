# Host install (`rtok agents install|uninstall|update|list|info`)

Port of `src/agents/mod.rs` (install engine), `src/agents/command.rs` and `src/cli/agents*.rs` (host side).

## How it works
- Every host has a descriptor in `Hosts` (variants: CLI and desktop apps, and what each can carry: `hooks`, `mcp`,
  `proxy`, `plugin`) and an install side in `Agents` (`files`, `installed`, `apply`, `pluginSurfaces`).
- `AgentsRun.run` (Install.kt) is one install, update or remove:
  1. Unknown hosts are refused, and so is a host with no wanted app on this machine (not for remove). The error
     names the key that points rtok at it: `rtok config set setup.paths.<host> <path>`.
  2. Every file the hosts may write is backed up to `.backup_configs/` first, so one generation holds each host as
     it was. The installers take no copies of their own.
  3. Each wanted variant gets one block (`Blocks.block`): header with the result, `app`, `config`, module rows
     (`mcp` one line per surface), plugin groups. A host whose apps share files (Cursor) is applied once.
  4. After install or update, each expected module is read back from the files; one that is not there prints
     `warning: <module> did not read back as installed`.
  5. A run that changed nothing deletes its backups; otherwise the backups are pruned to `[setup] backup_files`.
- `update` with no host updates every host that has an rtok module in an app on this machine, and keeps `--proxy`
  and `--yes` modules it finds installed.

## Enable / disable
- `rtok agents install <host>[,<host>…] [--cli|--desktop|--all] [--yes] [--proxy] [--mcp] [--replace] [--dry-run [--stat]]`
- `rtok agents uninstall|remove <host> [--yes] [--dry-run [--stat]]`; `rtok agents update [<host>] [--force] [--source github|local|marketplace]`
- `rtok agents list`, `rtok agents info <host>`.
- `[setup] mcp = false` skips MCP entries; `[setup] backup = false` skips backups.

## Test
`ModulesTest`, `InstallTest`, `InstallListTest`, `CommandTest` (`./gradlew macosArm64Test`) and `AgentsCliSpec`
(`./gradlew cliTest`, against a Windsurf config in a scratch home).

## Limitations
- Output is Rust's non-terminal form: marks, no colour or operation icons, no progress bar.
- Not ported yet: `--json` for list/info, `update --check` and `outdated`, `--project`, asking for a missing host's
  path on a terminal, the desktop app restart (`--no-restart` is accepted and has nothing to skip), the hook-client
  link, the installed plugin's version note, and the session/usage/junk/message subcommands.
