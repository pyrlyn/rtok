Creator 2026-09-27 (D35) overrode the T131 gate. `prompt_recall`, `startup_recall`, `handoff` and `spawn_brief` are off in both `config/default.toml` and `Memory` (`src/config/mod.rs`), so the hooks from T291 still inject nothing. `prompt_recall` is a count; on means 5, the same title count as `recall_titles`.

Plan:
1. Worktree `_worktrees/rtok-T292`. Depends on T291 so the events exist to fire.
2. Set `prompt_recall = 5`, `startup_recall = true`, `handoff = true`, `spawn_brief = true` in `config/default.toml` and the `Memory` defaults. Leave `recall_tokens` 200, `checkpoint_tokens` 400, `spawn_brief_tokens` 300, inject `budget_tokens` 800. Leave `[plugins.memory.embed] enabled = false`.
3. `prompt_recall` injects id and title only. `startup_recall` restores the newest `session:*` note inside 400 tokens. `spawn_brief` stays inside 300. Measurement rows for those four kinds still write. An empty or failing measurement does not flip the flags back.
4. Tests that pin the old off defaults (`prompt_recall_is_off_by_default`, the startup-off half of the session-end test) expect the new defaults and cover an explicit off. Schema drift stays green.

Check: `rtok config show` on a fresh config prints the four flags on; a hook fixture with defaults injects titles on `UserPromptSubmit` and a session note on startup; the same fixture with the flags set false injects neither; `just check`.

**Result (2026-10-04).** `prompt_recall = 5`, `startup_recall`, `handoff` and `spawn_brief` default on in `config/default.toml` (`[plugins.memory]`) and in the `Memory` struct (`src/config/mod.rs`), inside the existing token budgets.
