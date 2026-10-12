# AGENTS.md — `plugins/codex`

Agent rules for the **Codex** host package. Humans: [`README.md`](README.md). Shared rules: [`../AGENTS.md`](../AGENTS.md).

## Contracts

| Item | Location / rule |
| --- | --- |
| Manifest / entry | `.codex-plugin/plugin.json` → `hooks/hooks.json` |
| Marketplace | `.agents/plugins/marketplace.json`, one local entry `rtok` at `./` |
| Hooks | Claude-shaped hooks → `rtok hook <event> --host codex`; the `codex` rows of `src/agents/hook_events.rs`, which `src/agents/codex/` installs too |
| MCP | not shipped here (T275/D33): `rtok agents install codex` writes `~/.codex/config.toml`'s `[mcp_servers.rtok]` independently, plugin enabled or not |
| Installer | [`../../src/agents/codex/`](../../src/agents/codex/) writes the user files; it does not offer this plugin yet |
| Tests | `tests/codex_plugin.rs` |

## Do

- Keep the hook events equal to the installer's; change both and the test together.
- Keep README.md and AGENTS.md updated with behaviour changes.
- Call rtok CLIs instead of reimplementing policy.

## Do not

- Send Claude's bare `updatedInput` to Codex: it applies a rewrite only with `permissionDecision: "allow"` (`hooks::codex_output` adds it; `research.md` section 40).
- Dual-wire plugin and `rtok agents install codex` without documenting the conflict.
