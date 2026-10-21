After T90 (done): `plugins/antigravity/` carries `plugin.json` + `mcp_config.json` and **no hooks** — creator decisions 2026-09-21: the plugin is the only install path (no direct edit of `~/.gemini/config/mcp_config.json`), and per https://antigravity.google/docs/hooks/ `PreToolUse` cannot rewrite tool input and `PostToolUse` cannot add context. New host `antigravity` in `src/agents/antigravity/` (`mod.rs` + `README.md` with the module table and `## Docs`), registered in `HOSTS` and `host()`. Variants: CLI (`agy` on PATH) and Desktop (`Antigravity.app`), same files. `hooks` → `No` with the reason from T90; `mcp` → through the plugin only; `proxy` → `No` (no documented base-URL override). Missing `rtok`: the offer names `ketch install pyrlyn/rtok`.

Creator decision 2026-09-24 (symlink behaviour is still undocumented, neither surface is installed here): **CLI** prints the `agy plugin install <resolved plugins/antigravity path>` line behind `--yes` and never writes `~/.gemini/antigravity-cli/plugins/` (the Kimi rule, `Support::Offer("--yes")`); `installed()` reads `<cli_plugins_path>/rtok/plugin.json` naming `rtok`; remove keeps that copy with the `agy plugin uninstall rtok` line. **Desktop** links `plugins/antigravity` to `<plugins_path>/rtok` (default `~/.gemini/config/plugins/rtok`) through `HostPlugin` behind `--yes` (`Support::Flag("--yes")`, `default_install: false`); `installed()` reads `PLUGIN.ours`.

Plan:
1. `src/agents/antigravity/{mod.rs,README.md}`; register in `HOSTS` / `host()`; `HostPlugin` table in `src/agents/plugin.rs` gets `Antigravity` → `false`.
2. `[setup.antigravity] plugins_path` / `cli_plugins_path` in `config/default.toml`, `src/config/mod.rs` (section + path expansion), `docs/config.md`.
3. Unit tests: CLI dry-run without `--yes` is `NO_CHANGES`, with `--yes` names `plugins/antigravity`, `agy plugin install` and the ketch line and writes nothing; a staged CLI copy is reported installed and kept on remove. Desktop: dry-run writes nothing; `--yes` links, a second apply is `NO_CHANGES`, remove takes back exactly ours; a foreign directory is left alone and not reported installed.
4. Bless `docs/agents.md` (`RTOK_BLESS=1 tests/agents_doc.rs`), `tests/agents_install.rs` / `tests/common/agents.rs` host rows, trycmd config/report goldens (restore `v[..]` in `man.stdout`).

Result: new host `antigravity` (CLI + Desktop) in `src/agents/antigravity/`; shared `agents::print_offer` now backs both Kimi's `/plugins install` line and the `agy plugin install` line (no behaviour change for Kimi); `[setup.antigravity] plugins_path` / `cli_plugins_path`; `docs/agents.md`, config and report goldens re-blessed. Skill roots and the research sentence stay in T91.2.

Check: the unit tests above; `rtok agents list` shows `antigravity`; `agents_doc`, `host_docs`, `config_coverage` green; `just check`.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
