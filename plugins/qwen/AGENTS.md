# AGENTS.md — `plugins/qwen`

Agent rules for the **Qwen Code** host package. Humans: [`README.md`](README.md). Shared
rules: [`../AGENTS.md`](../AGENTS.md).

## Contracts

| Item | Location / rule |
| --- | --- |
| Manifest | `qwen-extension.json` → `name`, `version`, `description`. No `hooks` key and no `mcpServers` |
| Hooks | `hooks/hooks.json`, Claude event names → bare `rtok hook <event>` with `timeout` in seconds. Same `(event, matcher)` rows as `hook_events` for `qwen`. Settings.json gets the PATH resolver instead; do not copy that shell into this file |
| MCP | Not in this package. `src/agents/qwen` writes `mcpServers.rtok` into `settings.json` |
| Installer | [`../../src/agents/qwen/`](../../src/agents/qwen/) offers `qwen extensions link <this dir>` behind `--yes`; D21 strips `settings.json` hook entries while this extension is linked |
| Tests | `src/agents/qwen` `extension_hooks_follow_the_event_table`, `tests/hook_manifests.rs`, `tests/host_docs.rs` |

## Do

- Keep `hooks/hooks.json` on the `qwen` rows of `hook_events`, bare `rtok hook <event>`, timeout 5.
- Keep README.md and AGENTS.md updated with behaviour changes.
- Call rtok CLIs instead of reimplementing policy.

## Do not

- Add Gemini event names (`BeforeTool`) or a millisecond timeout. Qwen skips an unknown event and reads 1000 or more as legacy milliseconds.
- Add `--host qwen` to the command. Qwen's stdin is already Claude-shaped, and the settings writer matches `rtok hook <event>` with no host flag.
- Add a `skills/` directory here — skills live only in the repo's `skills/` (T234).
- Put `mcpServers` in `qwen-extension.json`. Settings own that entry so one `rtok mcp` serves the store.
