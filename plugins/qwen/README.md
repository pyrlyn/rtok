# rtok Qwen Code extension

One extension directory for Qwen Code (`qwen`): rtok's hooks as one unit (D21).
Qwen loads `hooks/hooks.json` from an extension whose `qwen-extension.json` does
not set `hooks`. The file is the same `{hooks: {<Event>: [...]}}` shape
`~/.qwen/settings.json` uses, with `timeout` in seconds and Claude's event names.

Install:

- `qwen extensions link <path to this folder>` — the local link Qwen documents.
  `rtok agents install qwen --yes` runs that line through the `qwen` CLI.
- Remove: `qwen extensions uninstall rtok` (the manifest's `name`).

`rtok` must be on `PATH` (`ketch install pyrlyn/rtok`); a hook fails open when it
is missing.

D21: while this extension is linked, `rtok agents install qwen` takes rtok's hook
entries out of `settings.json` instead of adding them. MCP is not in this
manifest. The installer writes `mcpServers.rtok` into `settings.json` on every
install, and that file wins over an extension server of the same name.

Files:

- `qwen-extension.json` — `name`, `version`, `description`. No `hooks` key, so
  Qwen reads `hooks/hooks.json`. No `mcpServers`: the settings file owns MCP.
- `hooks/hooks.json` — bare `rtok hook <Event>` (no `--host`) on PreToolUse
  (Bash, Read, Skill), PostToolUse (`*`), UserPromptSubmit, SessionStart,
  PreCompact, PostCompact, SessionEnd, SubagentStart. The same rows as
  `hook_events`. `settings.json` gets the PATH resolver; this file does not.

## Docs

Official documentation for the surfaces this package uses.

- Extensions (`qwen-extension.json`, `~/.qwen/extensions/`, `qwen extensions link` / `uninstall`): https://qwenlm.github.io/qwen-code-docs/en/developers/extensions/extension/
- Hooks (event names, command hooks, `timeout` in seconds, matcher aliases): https://qwenlm.github.io/qwen-code-docs/en/users/features/hooks/
- Settings (user `~/.qwen/settings.json`, where the installer writes MCP): https://qwenlm.github.io/qwen-code-docs/en/users/configuration/settings/

The extension page does not list a `hooks` field. The loader that reads
`hooks/hooks.json` when the manifest omits `hooks` is the repository:
https://github.com/QwenLM/qwen-code/blob/main/packages/core/src/extension/extensionManager.ts
