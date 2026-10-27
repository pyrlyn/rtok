# Host install (`rtok agents install|uninstall|update|outdated|list|info`)

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
- `rtok agents list`, `rtok agents info <host>` (`--json` for one row per app, with `plugin_version`/`plugin_source`).
- `rtok agents outdated [<host>] [--cli|--desktop|--all] [--json] [--exit-code]` (same as `update --check`): every
  installed rtok plugin older than this rtok, read from the plugin's `.rtok-plugin-version`, rtok's receipt or the
  host's own record (`PluginInstall` knows where each host keeps it). `--exit-code` exits 10 when one is behind.
  The `plugin` row of `list`/`info` names the installed version and source.
- `rtok agents sessions [--all] [--json]`: the Sessions page (store rows, newest first, sub-agents indented; ended ones
  with `--all`). `rtok agents sessions watch` redraws it in place on a terminal (cursor up, clear below; no raw mode)
  and reprints the whole table on a pipe when it changes; it ends when its parent goes away.
- `rtok agents whoami [--json]`, `rtok agents show <id-prefix> [--json]`, `rtok agents status <text>` (empty clears),
  `rtok agents send <id-prefix> <text|->` or `send --all-live <text|->` (`--task ID`), `rtok agents inbox [<id>]
  [--unread] [--task ID] [--json]`. The caller is `RTOK_AGENT_ID`, else the host session's main agent; the user at a
  terminal sends as `user`. Messages print framed with the fixed note; the host session id stays in `--json` only.
- `rtok agents usage [--source logs|rtok|both] [--host IDS] [--since DATE|DUR] [--until DATE] [--daily|--monthly]
  [--by agent|model] [--tz ZONE] [--unpriced] [--json]` (`[agents.usage]`): tokens and estimated cost per agent and
  month (or day). `logs` reads the agents' own session files (Claude Code, Codex, OpenCode, Kilo, Copilot CLI, Gemini
  CLI, pi, Kimi; droid, grok, zcode and antigravity are named as unsupported), `rtok` the store's `usage` rows, `both`
  the logs plus how much of each agent passed through rtok. Costs come from `[stats.prices]`; an unpriced model counts
  in the tokens only. Days and months are cut in `--tz` (system tzdata through the C library, no bundled zones).
- `[setup] mcp = false` skips MCP entries; `[setup] backup = false` skips backups.

## Test
`ModulesTest`, `InstallTest`, `OutdatedTest`, `SessionsTableTest`, `AgentMessagesTest`, `InstallListTest`, `CommandTest`, `UsageReportTest`, `UsageLogsReportTest`, `UsageLogsTest` and the `rtok.measure.usage` reader tests (`./gradlew macosArm64Test`) and `AgentsCliSpec`
(`./gradlew cliTest`, against a Windsurf config in a scratch home).

## Limitations
- Output is Rust's non-terminal form: marks, no colour or operation icons, no progress bar.
- `install|uninstall|update` quit a running desktop app whose config the write changes, write, then reopen it
  (RestartFlow.kt, macOS `osascript`/`open -a`); a running CLI gets a restart reminder; `--no-restart` skips it.
- `install|uninstall <hosts> --project` (cursor, kilo, windsurf, devin) adds or removes the `rtok worktree adopt`
  entry in the project's post-create file (PostCreate.kt); `rtok worktree adopt` itself comes with the worktree port.
- Not ported yet: asking for a missing host's
  path on a terminal, the hook-client
  link, and the junk subcommands.
