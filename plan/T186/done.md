Creator request 2026-09-22: host for Xiaomi MiMo — both the coding CLI (MiMo Code / `mimo`) and MiMo Desktop. Mobile/Termux builds of MiMo Code share the same config family where proven; treat Desktop as a second variant once its config paths are documented.

What MiMo is (research): Xiaomi’s AI coding stack. **MiMo Code** is an open-source terminal-native coding agent (https://github.com/XiaomiMiMo/MiMo-Code, docs https://mimo.xiaomi.com/mimocode/start), a fork of OpenCode with persistent memory, Compose mode, skills, LSP, and MCP; install via `curl -fsSL https://mimo.xiaomi.com/install | bash` or `npm i -g @mimo-ai/cli`, run `mimo`. Config: `~/.config/mimocode/mimocode.json` and project `.mimocode/mimocode.json` (`mcp`, `plugin`, agents build/plan/compose). File hooks live under `~/.config/mimocode/hooks/` (`*.ts` / `*.js`, `@mimo-ai/plugin`). **MiMo Desktop** is Xiaomi’s separate all-in-one desktop app (early access on mimo.mi.com) for office/design/coding — probe whether it shares `mimocode.json` MCP or has its own store before writing a Desktop variant. Community Android/Termux forks exist; only claim mobile if the same config paths apply.

Integration analysis: closest existing host is `opencode` (same fork lineage) — reuse MCP edit patterns, but packages are `@mimo-ai/*` not `@opencode-ai/*`, so an OpenCode plugin path will not load as-is (see also rtk-ai/rtk#2380). v1 should (1) write local MCP `rtok` into `mimocode.json`, (2) optionally drop a file hook that rewrites shell through `rtok run` like the OpenCode plugin, (3) add a Desktop variant only after confirming paths. D21 singleton: strip duplicate MCP when a linked plugin already carries it.

Plan:
1. Probe: `mimo` on PATH; read/write `~/.config/mimocode/mimocode.json` MCP; confirm Desktop config location (or document “CLI-only until Desktop paths known”). Note in `research.md` §15.
2. `src/agents/mimo/` — variants CLI (`mimo`) and Desktop (when paths known); MCP install/remove; optional `plugins/mimo/` or hooks file if the OpenCode-style plugin API is the honest bash-rewrite path.
3. Tests + `agents_doc` bless; trycmd list row.
4. Do not claim Termux/mobile as a separate variant unless install detection is distinct and stable.

Execution plan:
- Probed MiMo Code docs (research.md §15.4): binary `mimo`, config `~/.config/mimocode/mimocode.json`, `mcp.<name>` local shape identical to OpenCode's (`{type: "local", command: [..], enabled}`) — no `.jsonc`-only requirement, plain JSON write is enough for v1.
- `src/agents/mod.rs`: factored `register_local_mcp`/`unregister_local_mcp` out of `opencode`'s inline JSON so `mimo` reuses it verbatim (no duplicated logic, keeps `just dup` under 2 %); registered `mimo` in `HOSTS` and `host()`.
- `src/agents/mimo/mod.rs` + `README.md`: CLI-only variant (no Desktop — no documented config path), `mcp` = `Support::Yes`, `hooks`/`proxy`/`plugin` = `Support::No` with one-line reasons (no shell hook events, no base-URL override, no confirmed `@mimo-ai/plugin` package — do not ship a guessed plugin).
- `src/config/mod.rs` + `config/default.toml` + `docs/config.md`: `[setup.mimo] config_path`.
- `tests/common/agents.rs`, `tests/agents_install.rs`: added `mimo` to the shared fixture home and the install matrix.
- `docs/agents.md` via `RTOK_BLESS=1`; `tests/trycmd/{config-init.toml,config-show.stdout,report-md.toml}` via `TRYCMD=overwrite` (`man.stdout` untouched, no version placeholder to restore).
- Review fix: `mimo`'s first draft pushed `just dup` from 1.99 % to 2.05 % by inlining the same `installed`/`apply`/`support` boilerplate zed, windsurf, aider, codex and others already have, plus its own copy of `opencode`'s MCP round-trip test. Factored three more shared bodies into `src/agents/mod.rs` — `installed_mcp_only`, `apply_mcp_only`, `support_mcp_only` — and one `#[cfg(test)]` `assert_local_mcp_roundtrip` + `test_scratch_cfg`, and moved both `mimo` and `opencode`'s MCP round-trip test onto the shared assertion. `just dup` now reports 1.97 % (1479 lines, 170 clones), under the pre-`mimo` baseline.

Check: `rtok agents list` shows `mimo`; install adds rtok under `mcp` in mimocode.json; `just check`.

Result: `rtok agents install mimo` registers `mcp.rtok` (local argv shape, identical to OpenCode's) in `~/.config/mimocode/mimocode.json` (`[setup.mimo] config_path`); CLI variant only (`mimo`), since MiMo Desktop has no documented config path. `hooks`, `proxy` and `plugin` are `Support::No` with reasons: no shell hook events, no documented base-URL override, no documented `@mimo-ai/plugin` package. Docs findings in `research.md` §15.4. OpenCode and MiMo share the MCP helpers (`register_local_mcp`/`unregister_local_mcp`, MCP-only support/apply/installed, one round-trip test helper); `just dup` 1.97 %; `just check` green.

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
