MCP-only `docs` plugin (default off): resolve exact crate versions from `Cargo.lock`, search a local FTS index of cached rustdoc JSON, fetch from docs.rs only via `rtok docs fetch`. No Context7 client, no OAuth, no embeddings, no rustdoc-types.

Do: feature `docs`, `CATALOGUE` `("docs", false)`, `[plugins.docs]`, migration `0039_docs_fts`, store `src/store/docs.rs`, plugin `src/plugins/docs/`, MCP `docs_resolve` / `docs_query` / `docs_get`, CLI `rtok docs fetch`. `prepare_args` aliases `question`/`userQuery` → `query` on `docs_query` only. Workspace `llms.txt` / `llms-full.txt` index as crate `llms` version `local`.

Check: plugin off → hook output unchanged; fixture lock serde 1.0.210 → `docs_resolve` prints that version; seeded `Deserialize` ranks first under `max_tokens`; empty cache prints `not cached` with no socket; three tool descriptions ≤ 60 tokens; `just check`; `cargo test -p rtok docs::`; `cargo test -p rtok --test mcp`.

Result: default-off plugin; three MCP tools (descriptions ≤ 60 tokens); `docs_query` is FTS-only once cached and records `plugin: "docs"`, `kind: "query"` (`before_bytes` = cached docs considered, `after_bytes` = reply). `rtok docs fetch` is the only GET (docs.rs `json.zst`, 30s, no Authorization). No saving claim without that Measurement row. `cargo test -p rtok --lib docs::` 8 passed; `--test mcp` 8 passed; nextest 2979 passed, 6 skipped.

Status: done 2026-10-08

Model: Cursor / composer

Creator request 2026-10-08: find why `link::resolve` returned `Link::None` for a Claude Code desktop session whose agent row had matching `ancestors` and cwd. Finding: `resolve` is right. In the desktop app's Code tab every `mcp__rtok__*` call is served by the one `rtok mcp` Claude.app spawns from `claude_desktop_config.json` (`calls.session_id = mcp-<pid>` of that process; parent `disclaimer` → `Claude.app`, cwd of an unrelated project, no `CLAUDE_*` env), not by the session's child of `claude` (the `rtok@rtok` plugin's server), which never served a call in a day of the creator's store. Its parent chain and cwd match no session, so nothing can link it, and T454's `CLAUDE_CODE_SESSION_ID` rule does not reach it either. Replayed against a scratch `RTOK_HOME` (fresh store, then a `sqlite3 .backup` of the live store with the live config): a process inside the session's tree links by `ancestor`. The install-side fix is T456 (`roadmap.md`).

Check: `resolve` returns `Link::Outside` instead of `Link::None` when live agents of the host recorded hook chains since this process started yet none shares a pid with this process or its cwd, and MCP `whoami`/`worktree_*`/`agent_*` answer with that reason and the CLI to use; `Link::None` keeps its exact text; `just check`.

Result: `Link::Outside` in `src/agents/link.rs`, after the hookless rule (a hookless host sharing the `other` host row with a hooked one still registers its own row) and only when both this process and a live candidate have a chain (an old client without one could still be this session). Like `None` it is never cached, since a new session's MCP looks the same until its first hook lands. `src/mcp.rs` `agent()` maps it to "not linked to an agent session: this rtok mcp process is under no live <host> session and in none's cwd, …". `docs/agents-and-worktrees.md` (and `ru`/`uk`) gain a "Claude desktop app" section. Tests: three `link.rs` cases with fake pids in an in-memory store (outside; no chained row or no own chain stays `None`; hookless beside chained rows registers itself) and one RPC `whoami` case. Live: a debug build and the installed 0.15.1 against the scratch replay, with the session's chain swapped for fake pids and a foreign cwd: the new text vs the old one.

Status: done 2026-10-08
Model: Claude Code / claude-opus-5-5
