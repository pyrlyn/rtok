VS Code agent plugins carry hooks and MCP and are registered by path in the `chat.pluginLocations` setting (https://code.visualstudio.com/docs/agent-customization/agent-plugins). Verify the accepted format (Claude-format plugins?) and the hook event names first. `rtok agents install vscode --yes` adds the plugin path to `chat.pluginLocations` in the user `settings.json` (JSONC — see T79 before writing it) and strips its own MCP entry while the plugin is listed (D21).

Execution plan:
- Confirmed via the docs that VS Code accepts the existing `.claude-plugin/plugin.json` + `hooks/hooks.json` + `.mcp.json` layout as-is, so `plugins/claude/` is reused unchanged instead of shipping a `plugins/vscode/` tree.
- Extracted the JSONC surgical-editor logic T79 introduced in `src/agents/zed/mod.rs` into a shared `src/agents/jsonc.rs` (parametrized by top/entry key, with proper key escaping), and rewired Zed onto it to avoid duplicating the scanner.
- `src/agents/vscode/mod.rs`: `plugin` module (`Support::Flag("--yes")`) links `plugins/claude` into a fixed per-profile destination (`<user-dir>/plugins/rtok`, stable across ketch version bumps) via the existing `HostPlugin`/`PluginLink`, then registers that destination path as a key in `chat.pluginLocations` (JSONC-safe upsert/remove); D21 strips the direct `mcp.json` entry while the plugin is linked.
- Round-trip tests cover add/idempotent/remove with foreign entries and comments preserved, plus a full `apply()` E2E (link + settings + D21 MCP strip, and full reversal on remove).
- Updated `src/agents/vscode/README.md` (`## Docs` with fetch date 2026-09-24, modules table, reachable/not-reachable lists) and reblessed `docs/agents.md`.

Check: settings round-trip test (add, idempotent, remove keeps foreign entries); `just check` green.

Result: VS Code's `chat.pluginLocations` accepts the Claude-format plugin layout as-is, so `rtok agents install vscode --yes` links the existing `plugins/claude` tree into a fixed per-profile `<user-dir>/plugins/rtok` (stable and Insiders) and registers that path in `settings.json` with a comment-preserving JSONC edit. D21: while the plugin is linked, the plain `mcp.json` entry is stripped. Zed's JSONC editor (T79) was extracted into a shared `src/agents/jsonc.rs` and parameterized by key, so Zed and VS Code share one scanner. All 8 Zed tests are kept, plus new jsonc and vscode tests. The docs are cited in `src/agents/vscode/README.md` (fetched 2026-09-24). `just check` green; `just dup` 1.99 %.

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
