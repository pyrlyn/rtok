# Qwen Code

`rtok agents install qwen` — Qwen Code, the terminal agent forked from Gemini CLI.
One config directory, `[setup.qwen] dir` (default `~/.qwen`, moved by `QWEN_HOME`),
holds both hooks and MCP in `settings.json`. No desktop variant: the desktop app
is published, and the settings page names this one user file for every session.

## Modules

| Module | Support | Why |
| --- | --- | --- |
| hooks | yes | `hooks.<Event>[]` → `{matcher?, hooks: [{type: "command", command: "rtok hook <Event>", timeout}]}` with `timeout` in seconds, on PreToolUse (Bash, Read, Skill), PostToolUse (`*`), UserPromptSubmit, SessionStart, PreCompact, PostCompact, SessionEnd, SubagentStart |
| mcp | yes | `mcpServers.rtok` → `rtok mcp --host qwen` in `settings.json` as `{command, args}` (off with `[setup] mcp = false`) |
| proxy | no | Qwen Code's model.baseUrl is written by the model picker; modelProviders entries carry their own baseUrl, which setup does not edit |
| plugin | `--yes` | `qwen extensions link <plugins/qwen>` (D21). While linked, `hooks` above go instead of coming. MCP stays in `settings.json` on every install: that file wins over an extension server of the same name |

The hook command has no `--host`. Qwen's stdin is already Claude-shaped
(`hook_event_name`, `tool_name`, `hookSpecificOutput.permissionDecision`), and
`--host qwen` would not match the command the settings writer recognises.
`BeforeTool` / a millisecond timeout are Gemini's spelling; Qwen skips an
unknown event name and reads a timeout of 1000 or more as legacy milliseconds.
Matchers are the aliases Qwen accepts exactly (`Bash`, `Read`, `Skill`) and
`*` for every tool. Events rtok has no plugin for (`Stop`, `Notification`,
`PostToolUseFailure`, and the rest of Qwen's list) are left uninstalled.

## rtok plugins this host reaches

Hooks carry `hook` and `cli`, MCP carries `mcp`. Nothing carries `proxy`.

Reachable: measure, cmd, read, archive, inject, guard, memory, graph, toon, docs
Not reachable: proxy, compress

## Docs

Host documentation setup writes against; re-check the links when this host changes.

- Settings (`~/.qwen/settings.json`, user vs project, `QWEN_HOME`): https://qwenlm.github.io/qwen-code-docs/en/users/configuration/settings/
- Hooks (`hooks.<Event>[]`, event names, `matcher` / `type` / `command` / `timeout` in seconds): https://qwenlm.github.io/qwen-code-docs/en/users/features/hooks/
- MCP (`mcpServers.<name>`, stdio `command` / `args`): https://qwenlm.github.io/qwen-code-docs/en/users/features/mcp/
- Extensions (`qwen-extension.json`, `~/.qwen/extensions/`, `qwen extensions link` / `uninstall`): https://qwenlm.github.io/qwen-code-docs/en/developers/extensions/extension/
