Creator request 2026-09-24: the hook list lives in the plugin folder, and every install path takes it from there. Today `CLAUDE_ENTRIES` in `src/agents/claude/mod.rs` is the source and a test compares `hooks.json` against it, so a new event has to be written twice. Read the `(event, matcher)` pairs from `hooks.json` via `include_str!` at build time (no plugin build step; the GitHub plugin install and `rtok agents install claude` share one file). The shared `ENTRIES` list that Kimi and ZCode take stays a Rust constant.

Plan: replace the `CLAUDE_ENTRIES` const with a `LazyLock` that parses `hooks.json` in file order (a small serde map visitor; `serde_json` here has no `preserve_order`, so a `Value` would sort events and reorder install reports); `plugin_tree_matches_the_installer` keeps checking each entry's command and timeout, plus that the file starts with `ENTRIES`.

Check: `rtok agents install claude --dry-run` output is unchanged; `just check` green.

Do (Claude Code / claude-opus-5-5, 2026-09-24): `claude_entries()` parses `hooks.json` once through a `LazyLock` with a borrowed serde map visitor that keeps file order; the `CLAUDE_ENTRIES` const is gone. `plugin_tree_matches_the_installer` asserts the file starts with `ENTRIES` and carries `SubagentStart`. Merged as PR #351.

Check result: the 190 `agents::` unit tests pass (install report order unchanged); `just check` green except `pi_plugin::setup_pi_yes_links_remove_unlinks`, a vitest 5 s timeout under load that passed on rerun.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
