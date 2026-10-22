**T48.8 VS Code Copilot Chat host** · P2, 3/5 · `src/agents/vscode/{mod.rs,README.md}` (new), `src/agents/mod.rs`, `src/config/mod.rs`, `config/default.toml`, `docs/config.md`, `docs/agents.md` (blessed), `src/cli.rs`, `README.md`, `tests/agents_install.rs`, `tests/common/agents.rs`, `tests/trycmd/config-show.stdout`

From I-17. GitHub Copilot Chat in VS Code reads MCP from the user `mcp.json` (`servers.<name>`, `type: "stdio"`) in the VS Code profile dir; T46.4 covered only the Copilot CLI and the desktop app.

Do: `rtok agents install vscode` registers `servers.rtok = {type: "stdio", command, args: ["mcp"]}` in each edition's user `mcp.json` through the SDK `register_server`/`unregister_server` (key `servers` — not a copy of Copilot CLI `mcpServers`/`type: local`). User dir per OS: macOS `~/Library/Application Support/<Code|Code - Insiders>/User`, Windows `%APPDATA%/<product>/User`, else `~/.config/<product>/User`. `[setup.vscode] config_path` / `insiders_path` empty means those defaults. One Desktop variant (bins `code` / `code-insiders`). Hooks are `no`: VS Code documents Claude-format agent hooks (Preview; `.github/hooks/*.json`, user `~/.copilot/hooks`, stdin `hook_event_name` / `tool_name`) — that is not the Copilot CLI camelCase the T46.3 mapping serves, and `~/.copilot/hooks` is already the `copilot` host. Proxy and plugin are `no`. Foreign `servers` survive remove. Host table regenerated (`RTOK_BLESS=1` `tests/agents_doc.rs`).

Check: `agents::vscode::tests::user_dir_resolves_code_and_insiders_per_os`; `dry_run_names_the_file_and_creates_nothing`; `apply_is_idempotent_and_remove_keeps_foreign`; `readme_tables_match_support`; `tests/agents_install.rs` matrix (install twice / remove twice); `host_docs`; `agents_doc`; `config_coverage`.

Status: done 2026-09-18 · Model: Cursor / grok 4.6

Evidence: isolated worktree `.worktrees/T48.8` on `t48.8` (not cherry-picked, not pushed). `cargo test --lib agents::vscode` 3/3; `--lib agents::tests::readme_tables_match_support` ok; `--test agents_install` 9/9; `--test host_docs` ok; `--test agents_doc` (blessed) ok; `--test config_coverage` ok. `just check` not run against the dirty main tree.

Deviation: hooks not written (card condition: only if VS Code documents a hook file the T46.3 Copilot mapping can serve — it does not). No `plugins/vscode/` (plugin module is `no`). Commits split over the 3-file cap (host, install tests, config, docs, CLI/README, plan close).
