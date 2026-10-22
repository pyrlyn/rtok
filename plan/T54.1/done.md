**T54.1 Agents support table in docs** · P2, 2/5 · `docs/agents.md`, `tests/agents_doc.rs`, `site/content/docs/reference/_content.gotmpl`, `AGENTS.md`

Creator request: one documentation table of every agent app (CLI / Desktop), whether rtok links a plugin into it, and which rtok features reach it; agents must keep it current.

Do: `docs/agents.md` holds the table between `agents-table` markers: host, app, kind, the four install modules (hooks, MCP, proxy, plugin as `yes`, flag or `—`) and the catalogue plugins reached. `tests/agents_doc.rs` builds the same table from `HOSTS`, `Agent::variants`, `Agent::support`, `agents::reaches` and the plugin registry, and fails with the regenerate command when the doc is stale; `RTOK_BLESS=1` rewrites it. Site reference page `Agents`. AGENTS.md D21 line tells agents to regenerate after any host or surface change.

Check: `cargo test --test agents_doc` passes; editing one cell makes it fail with "host table is stale"; `RTOK_BLESS=1` restores it. Table includes aider (T48.7).

Status: done 2026-09-17 · Model: Claude Code / Fable 5.1

Evidence: `just check` in a clean worktree at ef6c6ff: 693 passed, 2 failed, 2 skipped; both failures reproduce at HEAD without this change (`surface_parity` unclassified `wrap` from T51.4; `agents_install` `remove_twice_says_no_changes_and_the_second_takes_no_backup` for OpenCode). `agents_doc` and `host_docs` pass after rebase on 0dfcad1.

Deviation: 4 files. Claimed and closed in one commit without a plan.md row, because plan.md and done.md held other agents' uncommitted edits; only this entry is staged in done.md.
