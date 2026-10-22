DeepSeek-Reasonix (https://github.com/esengine/DeepSeek-Reasonix), CLI plus editor extension. Its docs list MCP, skills, memory and hooks in `~/.reasonix/config.json` (lead, checked 2026-10-05: https://esengine.github.io/DeepSeek-Reasonix/configuration.html); confirm the keys before writing them.

Check: the T413 per-host check (step 5) for this host.

Plan: re-read configuration.html and write only the keys it names. Skills and memory stay out unless that page says setup may edit them.

Done: `src/agents/reasonix/` (`mod.rs`, `README.md`): one CLI variant, `reasonix` (or its `dsnix` shim) on PATH. One `[[plugins]]` table (`name = "rtok"`, `command`, `args = ["mcp", "--host", "reasonix"]`) in `<Reasonix home>/config.toml`, edited with `toml_edit` so comments, providers and other servers survive byte for byte; the array is keyed by `name`, so the table-keyed Codex and Grok helpers do not fit and no existing helper was reusable (`load_toml`, `judge_owned` and `toml_item_to_json` are). `[setup.reasonix] dir` moves the Reasonix home. The card's `config.json` lead is the legacy 0.x file; the documented global file is `config.toml` (`research.md` §32.15). Hooks (`settings.json`, own payload and tool names), proxy and plugin stay `Support::No`; no Desktop variant (no documented install path). Registered in `HOSTS` and `host()`, `config/default.toml`, `docs/config.md`, `docs/agents.md` (and `ru`/`uk`), `agents_install` matrix, list/real-config/host-docs tests and trycmd goldens.

Check: `agents_doc`, `host_docs`, `agents_install`, `agents_list_content`, `agents_real_config`, `config_coverage`, `cli_trycmd` and the `reasonix` unit tests green.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
