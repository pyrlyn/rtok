**T48.6 Zed host** · P2, 3/5 · `src/agents/zed/{mod.rs,README.md}` (new), `src/agents/mod.rs`, `src/cli.rs`, `src/config/mod.rs`, `config/default.toml`, `docs/config.md`, `docs/agents.md`, `README.md`, `site/content/docs/commands.md`, `tests/agents_install.rs`, `tests/agent_remove.rs`, `tests/common/agents.rs`, `tests/trycmd/config-show.stdout`

From I-17. Zed configures MCP as `context_servers` in `~/.config/zed/settings.json` (JSON with comments) and has no shell hook events; its agent can also use external agents over ACP.

Do: `rtok agents install zed` writes `context_servers.rtok = {command, args}` with a comment-aware textual edit (no JSONC crate): comments and foreign servers survive install and remove. CLI and desktop share one file (`shared()`). Support: mcp yes; hooks/proxy/plugin `no` with reasons. `[setup.zed] config_path` default `~/.config/zed/settings.json`. Host table regenerated (`RTOK_BLESS=1` `tests/agents_doc.rs`).

Check: unit tests in `zed/mod.rs` (dry-run, missing-file idempotent, comments/foreign servers, comment-only object left in place, malformed refuses to write); `agents_install` matrix row; `zed_remove_keeps_comments_and_foreign_servers`; `host_docs` README `## Docs` links; `agents_doc` table includes Zed CLI + Desktop.
Status: done 2026-09-17 · Model: Cursor / grok 4.6
Evidence: `cargo nextest run` zed unit + `agent_remove` zed + `host_docs` + `agents_doc` pass; `cargo clippy -p rtok --all-targets -- -D warnings` clean. Pre-existing `agents_install::remove_twice` failure on a real OpenCode 1.18.29 on PATH is unchanged (same as T48.7).
Deviation: `zed/mod.rs` is larger than the 200 LOC working agreement because JSONC has to be edited without a new crate; noted on the card.
