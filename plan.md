# rtok

https://github.com/pyrlyn/rtok

Token-reduction CLI for AI coding agents: hooks, MCP server, API proxy; measured reductions, pluggable methods.

## Cloud review findings (2026-10-08)

New bugs, dead code and moves from a read-only Cursor cloud review of `main` at `a060af58` (agent `bc-e07c8099-22ee-5d00-b1c7-3bd1ad623d52`; full report: `cloud/rtok.md` in the private `listepo/roadmap` repo). They take ids T457–T470, ordered P0, P1, P2, P3. **confirmed** means seen in the tree; **suspected** means plausible from the code but not proven (nothing was run on Windows). Line numbers are as of the review. The report's own labels T436–T449 are roadmap ids, not this plan's. T457, T458 and T459 are done (`done.md`). None of the rest is in the task table yet: to take one, add its row and a card with a `Check:` line.

| ID | Priority | Kind | Status | Where | Fix |
| --- | --- | --- | --- | --- | --- |
| T460 | P2 | bug | suspected | `src/plugins/read/hook.rs:154-164` | `same_path` compares case-sensitively (`a == b`, then `Path::ends_with`). Case-fold or canonicalize on Windows. |
| T461 | P2 | bug | suspected | `src/hooks/resident.rs:105` (Windows) vs `:80` (Unix) | The Windows resident exits only when `hook.lock` vanishes; Unix also exits when the socket vanishes. Mirror the Unix condition. |
| T462 | P2 | dead code | confirmed | `src/plugins/guard/mod.rs:464` | `guard::check_json` has no callers; the CLI uses `guard::check` (`src/cli.rs:2393`). Remove it. |
| T463 | P2 | dead code | confirmed | `crates/rtok-mcp/src/ops.rs:66-72` | `runs_rtok` is a weaker copy of `runs_bin` + `is_rtok_bin` (`crates/rtok-agent-sdk/src/lib.rs:519-525`, `src/agents/mod.rs:1693-1698`), which also accept `rtok.exe`, case folding and `current_exe()`. Delete it and call `runs_bin`. |
| T464 | P2 | move | suspected | `crates/rtok-agent-sdk/src/lib.rs:84-163` (`backup`) → crates-packages `file-backup` | Depend on the published `file-backup` once its behaviour is confirmed to match (that crate has open hardlink and symlink bugs of its own). |
| T465 | P2 | move | suspected | `tools/dist-generate.sh`, `tools/release.sh` → `pyrlyn/ci` | Fold the shared release steps into `pyrlyn/ci` if the copies in the other repos really match (not diffed). |
| T466 | P2 | move | suspected | `.github/actions/rustup-toolchain-cache` → `pyrlyn/ci/.github/actions/` | Used four times in `ci.yml`; share it from `pyrlyn/ci` so other repos can reuse it. |
| T467 | P2 | move | confirmed | `src/store/` (`mod.rs` is 5,275 lines) → `crates/rtok-store` | Issue #628 (P2 on GitHub): architecture work for incremental builds, not a bug. |
| T468 | P3 | dead code | confirmed | `src/plugins/toon/mod.rs:212` | `toon::decode` is test-only. Put it under `cfg(test)` if release builds should not carry it. |
| T469 | P3 | dead code | confirmed | `crates/rtok-agent-sdk/src/lib.rs:1086` | `copy_dir` is dead on Unix. Gate it with `cfg(not(unix))`. |
| T470 | P3 | move | suspected | `tools/test-changed.sh`, `tools/selective-check.sh` → `scoped-check` / `pyrlyn/ci` | Overlap was claimed but not diffed against cox or `pyrlyn/ci`. Compare first; move only what matches. |

Already tracked here, not added again: `src/render.rs` → `change-preview` is T416.1; per-host MCP code → `crates/rtok-mcp` and the unused `rtok_mcp::ops::apply` are T277; `OPERATION_ICONS` → a shared icon crate is T436.3; the test-only `VersionFile::write`/`::new` and the stale `#[allow(dead_code)]` on `read_installed` (`src/agents/plugin_version.rs:84`, `:112`, `:268`) are open on T279.

| # | Status | Priority | Complexity | Readiness | Agent |
| --- | --- | --- | --- | --- | --- |
| T124 | todo | P3 | 2 | 0% | |
| T131 | todo | P2 | 3 | 70% | |
| T132 | todo | P2 | 2 | 70% | |
| T134 | todo | P1 | 2 | 40% | |
| T156 | in progress | P3 | 3 | 50% | Claude Code / claude-opus-5-5 |
| T262.3 | todo | P2 | 2 | 0% | |
| T261 | in progress | P2 | 3 | 95% | Cursor / grok 4.7 |
| T275 | in progress | P1 | 4 | 80% | Claude Code / claude-opus-5-5 |
| T275.1 | in progress | P2 | 3 | 80% | Cursor / grok 4.7 |
| T276 | in progress | P2 | 5 | 0% | Claude Code / claude-opus-5-5 |
| T277 | in progress | P2 | 5 | 20% | Claude Code / claude-opus-5-5 |
| T278 | in progress | P1 | 3 | 90% | Claude Code / claude-opus-5-5 |
| T279 | in progress | P1 | 5 | 90% | Claude Code / claude-opus-5-5 |
| T281 | in progress | P1 | 3 | 70% | Claude Code / claude-opus-5-5 |
| T283 | in progress | P1 | 3 | 60% | Claude Code / sonnet-5 |
| T289 | in progress | P2 | 4 | 75% | Claude Code / sonnet-5 |
| T289.3 | todo | P2 | 3 | 0% | |
| T329 | todo | P2 | 5 | 0% | |
| T329.15 | todo | P3 | 5 | 0% | |
| T329.30 | todo | P3 | 2 | 0% | |
| T329.28 | todo | P3 | 4 | 0% | |
| T329.29 | todo | P3 | 4 | 0% | |
| T329.31 | todo | P3 | 3 | 0% | |
| T356 | in progress | P1 | 2 | 5% | Claude Code / claude-opus-5-5 |
| T369.1 | todo | P3 | 1 | 0% | |
| T370 | in progress | P1 | 4 | 90% | Claude Code / sonnet-5.5 |
| T378 | todo | P3 | 3 | 0% | |
| T385 | in progress | P1 | 5 | 20% | Claude Code / opus-5-5 |
| T385.3 | todo | P1 | 3 | 20% | |
| T385.9 | todo | P3 | 5 | 10% | |
| T385.10 | todo | P3 | 4 | 10% | |
| T385.11 | todo | P3 | 4 | 10% | |
| T385.12.2 | todo | P3 | 3 | 0% | |
| T394 | todo | P2 | 2 | 20% | |
| T395 | todo | P3 | 2 | 20% | |
| T396 | todo | P3 | 2 | 20% | |
| T398 | todo | P3 | 1 | 30% | |
| T404 | todo | P3 | 3 | 10% | |
| T405 | todo | P3 | 3 | 10% | |
| T413 | todo | P2 | 3 | 0% | |
| T413.3 | in progress | P2 | 3 | 0% | Cursor / grok 4.7 |
| T413.4 | in progress | P2 | 3 | 0% | Cursor / grok 4.7 |
| T413.5 | in progress | P3 | 3 | 0% | Cursor / grok 4.7 |
| T413.6 | in progress | P3 | 2 | 0% | Cursor / grok 4.7 |
| T413.7 | in progress | P3 | 3 | 0% | Cursor / grok 4.7 |
| T413.8 | in progress | P3 | 3 | 0% | Cursor / grok 4.7 |
| T413.9 | in progress | P3 | 3 | 0% | Cursor / grok 4.7 |
| T413.10 | in progress | P3 | 2 | 0% | Cursor / grok 4.7 |
| T413.11 | in progress | P3 | 2 | 0% | Cursor / grok 4.7 |
| T413.12 | in progress | P3 | 2 | 0% | Cursor / grok 4.7 |
| T413.13 | in progress | P3 | 2 | 0% | Cursor / grok 4.7 |
| T413.14 | in progress | P3 | 2 | 0% | Cursor / grok 4.7 |
| T413.15 | in progress | P3 | 2 | 0% | Cursor / grok 4.7 |
| T414 | in progress | P1 | 4 | 20% | Claude Code / opus-5.5 |
| T414.7 | todo | P3 | 1 | 0% | |
| T416 | in progress | P1 | 3 | 70% | Claude Code / claude-opus-5-5 |
| T416.1 | todo | P1 | 2 | 0% | |
| T416.2 | todo | P1 | 3 | 0% | |
| T416.3 | todo | P2 | 3 | 0% | |
| T416.4 | todo | P2 | 3 | 0% | |
| T428 | in progress | P2 | 3 | 85% | Claude Code / sonnet-5.5 |
| T436.3 | todo | P2 | 2 | 0% | |
| T436.4 | todo | P3 | 2 | 0% | |
| T441 | todo | P2 | 5 | 0% | |
| T476 | todo | P3 | 2 | 0% | |
| T477 | todo | P3 | 3 | 0% | |
| T479 | todo | P3 | 2 | 0% | |
| T481 | todo | P3 | 2 | 0% | |
| T480 | todo | P3 | 3 | 0% | |



### T131. Measure the spawn brief: cost row and on/off re-read share
Rule: a saving that is not a `Measurement` row does not exist, and the brief is a cost first. Needs T128 and T130.2.
Plan: T130.1's hook records a `Measurement` (`plugin: "memory"`, `kind: "brief"`) with the tokens it added (before = 0, after = brief) so the cost shows as negative saving; `rtok stats` `subagents` row splits the re-read share and sub-agent input tokens by "spawned with a brief" (the brief's archive id in the sub-agent's first user message) vs without.
Check: fixture with one briefed and one plain sub-agent asserts the split; after a dated window with the flag on, `research.md` §17 gets the measured net; default flips to on only if net tokens saved > 0 — otherwise the card closes with the number and T130 stays off.
Progress (2026-09-25): the cost row was already recorded by T130.1 (`memory`/`brief`, before 0, after = brief tokens); `tests/hook_spawn_brief.rs` now asserts one row per fired brief and none otherwise. `rtok stats` splits the `subagents` re-read share and input tokens into `brief` / `no brief`, detected by `measure::subagents::SPAWN_BRIEF_MARKER` in a sub-agent's first user message (a `handoff.rs` test pins it inside the brief's `INSTRUCTIONS`); fixture test `briefed_and_plain_subagents_split_the_reread_share`. Left: the dated window with the flag on, the net in `research.md` §17, and the default decision.
### T132. Ship a Haiku scout agent definition with the Claude Code plugin
`research.md` §17.3(4). Make the cheap path the default one: `plugins/claude/agents/rtok-scout.md` with `model: haiku`, `tools` limited to the rtok MCP `read`, `search`, `outline`, `explore`, `expand`, and a short system prompt — ranged reads only, never a whole file over the outline threshold, answer with `path:line` citations and no file dumps. Verify the plugin `agents/` directory format against the current Claude Code docs first and add the link to the `## Docs` list in `plugins/claude/README.md`.
Check: `rtok agents install claude` offers the agent file and removal takes it away (host matrix e2e); `tests/host_docs.rs` and `tests/agents_doc.rs` (`RTOK_BLESS=1`) green; T128's per-`agentType` split is the measurement — record `rtok-scout` vs `Explore`/`general-purpose` read bytes per sub-agent in `research.md` §17 after a dated window.
Progress (2026-09-24): `plugins/claude/agents/rtok-scout.md` ships with the plugin (`model: haiku`, the five tools under the plugin-scoped names `mcp__plugin_rtok_rtok__<tool>`); unit test on the frontmatter, install/remove e2e in `tests/claude_plugin.rs`. Left: the dated `rtok-scout` vs `Explore`/`general-purpose` read-bytes row in `research.md` §17 once a window of sessions has run with it.
### T134. Probe: does a CLI command hook's `PostToolUse` `updatedToolOutput` replace native tool output?
Gate for I-91 (`research.md` §17.2). The Agent SDK hooks page says `updatedToolOutput` "works for any tool"; rtok's standing rule says PostToolUse can only add context. If the CLI honours it, native Read/Bash output could be shrunk in place (pointer + `expand <id>`) instead of wrapped or denied — that changes the design of `cmd`, `read` and `guard`, so it is a creator decision, not a silent change. No product code in this task.
Plan: throwaway hook script (scratch, not committed) returning `hookSpecificOutput.updatedToolOutput` for `Read` and `Bash` on the current Claude Code; run one Read and one Bash; check what the model received in the transcript. Repeat for an MCP tool.
Check: a dated row in `research.md` §3 with the Claude Code version, the payload sent and what the transcript shows, per tool kind. Honoured → the `AGENTS.md` rule line and I-91 are put to the creator with the row; not honoured → I-91 closes with the date.
Progress (2026-09-25, `research.md` §3 "T134"): Claude Code 2.1.267; the CLI hooks page lists no `updatedToolOutput` for `PostToolUse` (only `additionalContext`, `systemMessage`, `terminalSequence`), the Agent SDK page does. The live run is blocked in agent sessions (`claude -p` fails with an expired OAuth session, as in T53.1); left: the creator runs the scratch probe (hook logging the payload and returning `updatedToolOutput`, one Bash / Read / MCP call on `--model haiku`) from a real terminal, and the per-tool verdict goes into the row.
### T124. Realized `tools_rewrite` saving as a dated `research.md` row
6.2 % (T59.5) is the ceiling, not a saving: no dated row shows what `[proxy.tools_rewrite]` removes with the default `max_description_tokens = 60`. Precondition, by the creator: turn it on for this machine's proxy for at least 20 sessions. Then sum the `kind = tools_rewrite` Measurement rows against session input for the same window (`rtok stats` / `rtok gain`, dated command in the row), and write one row into `research.md` §2 next to the T59.5 row; update `docs/comparison.md` only if it cites the number. If the realized share is under the 3 % gate, say so in the row and leave the default off.
Check: the row cites the command, date, sessions, before/after tokens and the share; no number in prose without it.

### T156. Probe: `WorktreeCreate`/`WorktreeRemove` hooks and reflink-seeded `target/`

No product code. Two open questions from `research.md` §18.3–18.4: (1) Claude Code's `WorktreeCreate`/`WorktreeRemove` hooks replace the default create/remove — can rtok own location, naming and the ownership record there, and what do the desktop app and sub-agent `isolation: worktree` actually send; (2) does seeding a new worktree's `target/` by reflink (`reflink-copy`, APFS `clonefile`) save build time and disk after a real task, or does cargo rewrite most of it anyway.

Plan: throwaway hook script (scratch, not committed) that logs the payloads for `claude --worktree`, a sub-agent worktree and the desktop app, and returns a path under `_worktrees/`. For (2): two fresh worktrees of this repo, one seeded with `cp -c -R target`, one cold; record wall time of `just check` and physical disk delta (`df`, not `du` — clones are double-counted) for each. Write the payloads, the numbers and the dated commands into `research.md` §18. `reflink-copy` is a new dependency: adopting it is a creator decision taken on those numbers, not part of this task.

Progress (2026-09-25, `research.md` §18.4 second data point): part (2) measured. Cold `just check` took 185 s and +6.28 GiB; seeded took 371 s and +3.35 GiB. The clone skipped every dependency rebuild (≈ 23 s saved), but T236's `dunnage` pass then compressed the cloned files (≈ 215 s). Parked as I-99, with no follow-up task. Lead (unmeasured, 2026-10-08): the installed `dunnage` 0.1.0 has its own `seed` and `worktree add` subcommands (`dunnage --help`), so seeding may not need `reflink-copy`. Part (1), the hook payloads from `claude --worktree`, a sub-agent worktree and the desktop app, is still open: it needs live sessions of the creator's.

Check: `research.md` §18 gains the hook payloads and a dated table (cold vs seeded: seconds, bytes); T159's card is corrected against the recorded payloads; seeding gets a follow-up task or an `ideas.md` entry from the numbers; no file under `src/` changes.

Execution (2026-09-27): (1) a probe kit in the session scratchpad (never committed), like T281, that logs `WorktreeCreate`/`WorktreeRemove` payloads and returns a `_worktrees/` path; the creator runs it with `claude --worktree`, a sub-agent `isolation: worktree` and the desktop app. The documented payload fields go into `research.md` §18.3 now, with sources. (2) Seeded (`cp -c -R target`) and cold worktrees of this repo: wall time of `just check` and physical disk delta (`df` before/after, not `du`). The cold run only happens with ≥ 30 GiB free; otherwise the row says so. Result: a dated row in `research.md` §18.4.

### T262.3. Codex: spawn brief on `SubagentStart`

`research.md` §23: Codex fires `SubagentStart` and adds the hook's stdout (or its hook-specific context) to the subagent as developer context. Add `SubagentStart` to `plugins/codex/hooks/hooks.json` and the Codex installer's list, and make `rtok hook SubagentStart` answer in the shape Codex reads.

Blocked (found 2026-09-24 while claiming): the brief is built from `PreToolUse` rows whose `tool_name` is `Read|Edit|Write` (`ledger()` in `src/plugins/memory/handoff.rs`), and rtok installs no `PreToolUse` hook for Codex (only `PreCompact`/`PostCompact`), so a Codex brief would always be empty. Needs Codex `PreToolUse` wiring first (idea I-88), which the creator has not approved.

Check: a Codex `SubagentStart` payload through `rtok hook` returns the brief in Codex's shape (test); `just check` green.

### T261. CI takes ~9.5 min on macOS; the webui check recompiles 183 crates every run

Creator request 2026-09-24: find what makes CI and the tests slow, try fixes in a draft PR, do not merge. Measured on #326:
- `check (macos-latest)` is the critical path. Of its ~9.5 min: mise 53 s, cache restore 65 s, fmt 3 s, clippy 46 s, nextest's test-profile build 1 min 47 s, 1733 tests 79 s, then about 39 s of `build-min`, jscpd, oxlint and pytest.
- `just webui-check` takes 2 min 6 s on macOS and 1 min 33 s on ubuntu. `crates/rtok-webui` is excluded from the workspace, so its `target/` is not in `rust-cache`, and 183 crates compile from scratch on every run.
- 78 `tests/*.rs` files mean 78 test binaries to link.

Plan (a draft PR; each change measured with `workflow_dispatch` runs on the branch, warm cache):
1. `rust-cache` also caches `crates/rtok-webui/target`.
2. A `lint` job on ubuntu takes fmt, clippy, `build-min`, jscpd, JS, Python and `webui-check`. The `check` matrix keeps its name and runs only the tests and examples, so macOS stops paying for lint. Trade-off to report: clippy no longer runs on macOS-only `cfg` code.
3. `CARGO_PROFILE_DEV_DEBUG=0` in CI only (smaller test binaries, less linking).
4. Estimate folding `tests/*.rs` into one integration binary (78 links become 1); report it, do not do it here.

Check: warm-cache `workflow_dispatch` runs on the draft PR are green, and a PR comment gives before/after timings per job.

Progress (Cursor / grok 4.7): items 1–3 already on `origin/main` via #332 — do not redo. Draft PR https://github.com/pyrlyn/rtok/pull/411 (`t261-ci-timings`). Two `workflow_dispatch` runs posted timings in a PR comment; both runs **failed** (Check not met — card stays open).

**Blocker (exact errors from the logs):**
1. `lint` — `just … dup` / jscpd: `ERROR: jscpd found too many duplicates (2.1%) over threshold (2.0%)` (`.jscpd.json` `threshold: 2`; 198 clones). Same on both runs. `webui-check` / `publish-dry` and lint’s rust-cache save were skipped because of this.
2. `windows` — `cargo nextest` exit 1. Failures (warm run [36202660499](https://github.com/pyrlyn/rtok/actions/runs/36202660499)): `agents::devin::tests::plugin_manifest_matches_the_installer`; `agents_install::{dry_run_setup_creates_nothing_and_copies_nothing, setup_twice_takes_one_backup_and_says_already_installed, remove_twice_says_no_changes_and_the_second_takes_no_backup, list_reports_installed_modules_per_host}` (5 failed; cold run [36201850046](https://github.com/pyrlyn/rtok/actions/runs/36201850046) also failed `plugins::checkpoint::tests::session_end_on_a_large_transcript_is_bounded`).

`check (ubuntu-latest)` and `check (macos-latest)` were green both times; cold→warm job wall: ubuntu 161 s → 146 s, macOS 362 s → 222 s (see PR #411 comment).

Fold estimate (not folding): **82** `tests/*.rs` bins. macOS log `Finished test profile`: cold **2 m 05 s**, warm **1 m 16 s**. No per-binary `Linking` lines in the CI log. Fold not in this PR (82-file mechanical move; own task).

### T275. Install/update removes rtok's MCP entry from an agent's config while a plugin serves MCP (every host)

Observed by the creator on 2026-09-27: rtok 0.10.0, Claude Code 2.1.267, `rtok@rtok` plugin installed. After `rtok agents update claude` the plugin (commit `12c7e91`) and its hooks are current, but rtok's MCP server is missing from Claude Desktop's MCP settings. It must be available in both Claude Desktop and Claude Code.

Evidence on that machine:
- `~/Library/Application Support/Claude/claude_desktop_config.json` has no `mcpServers` key. The backup written by the same run (`_backup/claude_desktop_config.json.bak-1790511161`, 15:12) still holds `mcpServers.rtok` = `/Users/<user>/.ketch/bin/rtok mcp`, so the update removed it.
- Claude Code gets rtok's MCP only from the plugin (`.mcp.json` runs `scripts/mcp.sh`, which runs `rtok mcp`). `~/.claude.json` and `~/.claude/.mcp.json` have no `mcpServers.rtok`.
- `rtok agents info claude` still prints `✓ mcp installed` for Claude Desktop.

Cause (code on `main`, `12c7e91`). The D21 singleton rule ("while a plugin serves MCP, the config-file entry goes") is applied on every install and update, and the Claude Desktop case extends it to a different app:
- `src/agents/claude/mod.rs:724-737`, `Claude::apply` for `Kind::Desktop`: when `code_serves_mcp(cfg)` is true it calls `unregister_mcp_ours(cfg, &desktop_path(), "rtok")` instead of `register_mcp`. `Mode::Update` takes the same branch as install.
- `src/agents/claude/mod.rs:422-426`, `code_serves_mcp` is true whenever the Claude Code plugin is installed, so the desktop entry is always removed.
- T243/T244 assumed the desktop file only feeds the desktop app's Code tab. It is also the only place Claude Desktop's own chat reads MCP servers from, so removing it takes rtok out of Claude Desktop entirely.
- The read side hides it. `Claude::installed(Kind::Desktop)` (`mod.rs:688-694`) reports `mcp` when the file has `"rtok"` *or* `code_serves_mcp`. `Claude::installed(Kind::Cli)` (`mod.rs:700-706`) and most hosts below count `mcp` as installed when the plugin is present.

Hosts with the same pattern (install/update removes, or never writes, the agent-config MCP entry while a plugin serves MCP):
- Claude Desktop: `claude/mod.rs:724-737`, removes `mcpServers.rtok` from `claude_desktop_config.json` while the Claude Code plugin is installed.
- Claude Code: `claude/mod.rs:764-767`, removes `mcpServers.rtok` from `~/.claude.json` while the plugin is installed.
- Cursor: `cursor/mod.rs:113-117` with `plugin_is_mcp` (`:246`), never writes `~/.cursor/mcp.json` while the plugin is linked.
- Copilot CLI and Gemini CLI: the shared `d21_plugin_apply` (`agents/mod.rs`, the `if remove || plugin_installed(cfg)` branch), removes `mcpServers.rtok` from `~/.copilot/mcp-config.json` and from Gemini's settings.
- Codex: `codex/mod.rs:334-337`, `run(cfg, true)` removes `[mcp_servers.rtok]` from `~/.codex/config.toml` while the plugin is enabled.
- VS Code and VS Code Insiders: `vscode/mod.rs:140-148`, removes `servers.rtok` from `mcp.json` while the plugin is linked (T196).
- ZCode: `zcode/mod.rs:257-260`, removes `mcp.servers.rtok` while `plugin_serves`.
- Kimi: `kimi/mod.rs:127-130` (`plugin_detected`), removes rtok's tables.
- Grok Build: `grok/mod.rs:97-101` (`plugin_detected`), removes `[mcp_servers.rtok]`.
- Not affected (MCP is written on install/update regardless of a plugin): Kilo, OpenCode, Cline, Devin, Windsurf, Zed, MiMo, CodeWhale. Check omp, pi and Antigravity (they link a plugin; verify whether their plugin serves MCP and whether their config entry is skipped) and add them here if they match.

Decision (record as a decision row; it amends D21 for MCP and replaces T271's "sweep" item and the MCP half of T243/T244):
1. Install and update always write rtok's MCP entry into each agent's own config. Only `remove` takes it out. A plugin being installed no longer suppresses or strips it.
2. Duplicates with the same name are left to the agent to merge (Claude Code merges same-name servers across scopes). All config-file entries keep the name `rtok`.
3. Where two entries could conflict by name on one surface (the agent errors or refuses on a duplicate name instead of merging), use a different name per surface, recorded in the host's README row.
4. Hooks keep the D21 singleton rule; this task changes MCP only.

Architecture principle (applies to every step below and to T275.1):
- One shared MCP core for all hosts: install, update, remove, status (`installed`), the `doctor` check and `ping` are one implementation in `src/agents/mod.rs` (or a new `src/agents/mcp.rs`), parameterised only by data: the host's config file path(s), the JSON/TOML key path (`mcpServers`, `servers`, `context_servers`, `mcp.servers`, `[mcp_servers]`), the server name(s) per surface, the command form (bare `rtok` or absolute path), and the entry shape (`type`, `args`, `env`).
- Host-specific differences live in one table, not in code branches: a per-host `McpSpec` entry (for example a `const` slice or a method on `Agent` that returns data) with those fields plus how the agent treats duplicate names (merges same-name entries, overrides by scope, errors, or shows two servers), whether a plugin also serves MCP and under what name, and the headless ping command for T275.1. `docs/agents.md` is generated from that table.
- No per-host `if plugin …` / `if kind == Desktop …` branches for MCP. The host `apply` / `installed` code calls the shared core with its spec. A new host is added by adding a table row and a Vfs test row, not new MCP logic. The per-host MCP functions this replaces (`register_mcp`, `unregister_mcp`, `plugin_is_mcp`, `code_serves_mcp`, the MCP half of `d21_plugin_apply`) are deleted once every host uses the core.
- Tests are table-driven too: one Vfs test iterates every row and runs install, update, remove, status and ping against it.

Fix:
1. For each host above, verify against the agent's docs and one real run how it treats two MCP servers named `rtok` from different sources (merge, override by scope, error, or two servers), and whether a plugin-served server is namespaced (Claude: `plugin_rtok_rtok`, so it never merges with `rtok`). Record per host in `research.md` with the agent version and a docs link. This decides where rule 3 applies.
2. Write side: change each listed `apply` so that install and update call `register_mcp` whenever `cfg.setup.mcp` is set, and `unregister_mcp` only on `Mode::Remove`. For the Claude Desktop case, drop the `code_serves_mcp` branch in `claude/mod.rs:724-737`. For Copilot and Gemini, change `d21_plugin_apply` once. Keep T246's ownership check, so a user-edited entry is left and reported.
3. Name conflicts: where step 1 shows a surface sees both a plugin server and the config entry as two servers (for example the Claude desktop Code tab, T271), give that pair distinct names or drop the plugin's MCP server (keeping its hooks) so the agent merges one `rtok`. Pick per host from step 1 and note it in the decision row.
4. Honest status: `installed()` reports `mcp` only when the agent's own config file really holds rtok's entry. Remove the `|| code_serves_mcp(cfg)` fallback in `claude/mod.rs:688-694` and the "plugin implies mcp" shortcut in the other hosts; show a plugin-served MCP as its own line (for example `✓ mcp (plugin)`) so it never stands in for the config entry.
5. `doctor`: warn per host when the agent is installed but its config has no rtok MCP entry, naming `rtok agents install <host>`. Keep T171's duplicate check only where step 1 shows the agent does not merge.
6. Tests (Vfs, no host disk, D29), for every listed host: (a) plugin installed plus `install`, then the config has rtok's MCP entry with the right command and args; (b) the same after `update`; (c) `remove` takes it out; (d) config without an entry plus the plugin, then `installed()` does not report `mcp` from the config and `doctor` warns; (e) a user-edited entry is kept with a `leave` line; (f) the T244 surface count is updated to the new rule: at most one merged rtok server per surface, or distinct names where step 3 applies. Re-bless `docs/agents.md` and each host's README MCP row with `RTOK_BLESS=1`.

Check: the tests above pass; on the creator's machine `rtok agents update claude` leaves `mcpServers.rtok` in `claude_desktop_config.json`, Claude Desktop's MCP settings list rtok, the Code tab and Claude Code list one rtok server, `rtok agents info|list` reports every host truthfully, and a Cursor/Codex/Copilot update keeps their config entries; `just check`.

Execution plan (lands before T277; T277 then moves the core into its crate):
1. Worktree `_worktrees/rtok-T275`, branch `t275-mcp-entry-always`. Research (Fix 1) per host into `research.md`, with version and docs link.
2. PR A, Claude Code and Desktop: shared MCP core in `src/agents/mcp.rs` (write/remove/status by spec), decision row amending D21, drop `code_serves_mcp` in `apply` and `installed`, Vfs tests (a)-(f) for Claude.
3. PRs B-D, the other affected hosts in groups of at most 10 files: Copilot and Gemini (`d21_plugin_apply`); Cursor, Codex, VS Code; ZCode, Kimi, Grok. Each deletes that host's MCP branch and adds its table rows to the shared Vfs test.
4. PR E: `doctor` warning (Fix 5), re-bless `docs/agents.md` and README MCP rows, then the creator-machine Check.
Progress (2026-09-28): PR A and B-D merged for Claude, Copilot, Gemini, Codex, Cursor (#444), VS Code (#455), ZCode (#459), Kimi (#467); Grok is #468. Left: PR E.
Each PR listed here becomes a subtask (`Tn.m`, D16) when it is claimed; this card stays the epic.

### T275.1. `rtok mcp ping <agent>`: prove the agent's rtok MCP server is alive and answering

`installed` only says a config entry exists (and today not even that, see T275). This command checks the real path: the agent starts rtok's MCP server from its own config, calls a tool, and writes the answer into its chat.

Syntax: `rtok mcp ping <agent> [--cli|--desktop] [--timeout <secs>] [--json]`. `<agent>` takes the same host names as `rtok agents install|info` (`claude`, `cursor`, `codex`, `copilot`, `gemini`, `vscode`, …); `--cli` / `--desktop` picks the variant like `agents install`; no agent means every host `agents list` shows with MCP installed. Place it in `src/cli.rs` as a `ping` subcommand of `rtok mcp`, next to `--call`. The foreign-server wrap stays behind `--` (`rtok mcp -- <argv>`), so `rtok mcp ping …` must not be parsed as a wrap argv: add a clap test for both forms. Add a matching line to `rtok agents info` help that points at it.

What it sends:
1. A new MCP tool `ping` in rtok's own server (`src/mcp`), taking `{"agent": "<display name>"}` and returning exactly `MCP <display name> жив`, where the display name is the host name `agents list` prints (for example `MCP Claude Desktop жив`, `MCP Claude Code жив`, `MCP Cursor CLI жив`). The call is logged like any MCP call, with no measurement row.
2. For hosts with a headless CLI, rtok runs the agent once with one prompt: `Call the rtok MCP tool "ping" with agent="<display name>" and reply with its result only, nothing else.` For example `claude -p …`, `codex exec …`, `cursor-agent -p …`, `copilot -p …`, `gemini -p …`. The exact non-interactive flag per host is verified against its docs and recorded in the host README.
3. For desktop-only hosts (Claude Desktop, VS Code chat, Windsurf, Zed …) there is no documented way to send a prompt from outside. rtok prints the same prompt for the user to paste into the app's chat, then does the server-side part itself: it spawns the MCP command exactly as written in that host's config (`command` / `args` / `env`), runs `initialize`, `tools/list` and `tools/call ping`, and checks the reply. That proves the configured server starts and answers, but not that the app loaded it; the output says which of the two was checked.

Success: the agent's chat reply (captured stdout for headless hosts) is exactly `MCP <display name> жив`, after trimming whitespace. Anything else is a failure: no rtok entry in the host's config, the server fails to start, the `ping` tool is missing, a timeout (default 60 s), or a different reply. Each failure prints its reason and the fix (for a missing entry, `rtok agents install <agent>`). Exit code 0 only when every checked host succeeds. `--json` prints one object per host: `agent`, `variant`, `mode` (`chat` or `server-only`), `reply`, `ok`, `reason`.

Tests: unit test for the `ping` tool reply text; clap tests for `rtok mcp ping` vs `rtok mcp -- …`; a Vfs test that the server-only check reads `command` / `args` from each host's config (rtok's entry, not a hardcoded path); an e2e that spawns `rtok mcp` through a fake host config and gets `MCP Test жив`; headless hosts are covered by a fake agent binary on PATH that runs the MCP call and echoes the result.

Check: on the creator's machine `rtok mcp ping claude --cli` prints `MCP Claude Code жив` from `claude -p`, `rtok mcp ping claude --desktop` checks the server from `claude_desktop_config.json` and prints the prompt, and after T275 both succeed; `just check`.

Execution plan:
1. `src/mcp.rs`: tool `ping`, always listed, reply `MCP <agent> жив`, logged through the existing call row (no measurement).
2. `src/cli.rs`: `ping` subcommand of `rtok mcp` beside `--call`; wrap stays behind `--`. `agents info` long help points at `rtok mcp ping`.
3. `src/mcp/ping.rs`: headless rows for claude (`-p`), codex (`exec`), cursor (`cursor-agent -p`), copilot (`-p`), gemini (`-p`), each recorded in that host README with the docs link. Every other variant is server-only: read `command` / `args` / `env` from `Agent::files`, spawn through `doctor`'s MCP round-trip (`initialize`, `tools/list`, `tools/call ping`).
4. Tests: ping reply text; clap `mcp ping` vs `mcp --`; Vfs bodies for every host's MCP shape; e2e spawn of `rtok mcp` from a fake config (`MCP Test жив`); fake `claude` on PATH.
5. Verify with `just check`. The creator-machine `claude --cli` / `--desktop` run stays open.

### T276. Spinner audit: every place rtok runs an external command and the user waits for its output

Goal: whenever rtok starts an external process and a person at a terminal waits for its result, a spinner is on screen from the moment the process starts until its output arrives, with a message that says what is happening (`installing plugin via claude…`, `probing MCP server context7…`, `creating worktree…`). Use the existing helpers in `src/render.rs` (`loader`, `spinner`, both silent when stderr is not a TTY) and `with_loader` in `src/cli.rs`; no new spinner code.

Audit (first pass, `rg 'Command::new|tokio::process'` on origin/main `12c7e91`): 39 call sites in 16 production files (about 10 more are in tests and do not count). No `tokio::process` use. Step 1 of the fix confirms this list.

Already covered (spinner shown today):
- `rtok agents list` / `rtok agents info`: `app_version` (`agents/mod.rs:297`, `<bin> --version`) runs inside `with_loader("listing hosts" / "reading host")`.
- `rtok agents install/update/remove` when stdin is not a TTY: the 10 calls in `agents/restart.rs` (`tasklist`, `pgrep`, `osascript` x2, `taskkill`, `killall`, `open`, `cmd`, direct spawn, `xdg-open`) and the host plugin CLI calls through `run_cli` / `spawn_cli` (`agents/mod.rs:248,252`) run inside `with_loader("updating host")`.
- `rtok graph index`: LSP servers spawned in `graph/lsp.rs:202` run under `render::spinner("indexing")`.

Missing, output awaited, spinner to add:
1. `rtok agents install/update/remove` in an interactive terminal (`apply_hosts`, `cli.rs`): T81 turned the loader off entirely so it never draws over the plugin question. Instead, show a loader per step (`installing plugin via <bin>…`, `closing <app>…`, `reopening <app>…`) and stop it before any prompt and restart it after the answer. Covers the same `restart.rs` and `run_cli` sites.
2. `rtok doctor` MCP probe (`doctor.rs:924-938`, `spawn_mcp` / `mcp_command`): starts each configured MCP server (often `npx` / `uvx`, several seconds cold) and waits for `tools/list`, with nothing on screen. Add `probing MCP server <name>…` per server.
3. `rtok worktree add/gc/clean` (`worktree/git.rs:11`, the shared `git` helper): `git worktree add` and removal can take seconds on a large repo. Add a loader around the long `git` calls (`creating worktree…`, `removing worktree <id>…`); keep quick reads (`rev-parse`, `worktree list`) without one.
4. `rtok bench` (`bench.rs:268`, `bench.rs:378`, `claude -p`): each run takes tens of seconds to minutes and prints nothing until it ends. Add a loader with the arm, the prompt number and elapsed time (`bench mcp 3/10…`).
5. `rtok run <command>` (`plugins/cmd/run.rs:150`, `shell_command`): if the output is captured and printed only when the command ends, add `running <command>…`; if it streams live, add nothing (a spinner would interleave with it). Decide from the code in step 1.

No spinner needed (checked, with the reason):
- No person waits, or no terminal: `build.rs:12` (build time), `demon.rs:182,518` (supervised children), `bin/rtok-hook.rs:85` and `hooks/mod.rs:467` (hooks run by the agent), `otel/export.rs:496` (detached flush), `mcp/wrap.rs:44` (stdio proxy), `graph/watch.rs:720,740,759` (background watcher).
- The child owns the terminal: `log.rs:196` (`tspin` viewer), `demon.rs:315,317,326` (`rtok demon upgrade` hands the terminal to `ketch` / `rtok-update`, which print their own progress).
- Returns in milliseconds: `demon.rs:603` (`sysctl`), `bench.rs:88` (`sh` probe), `graph/lsp.rs:21,32` (`on_path`, `rustup which`), `graph/mod.rs:755` (`git diff --name-only`).

Architecture: one `ProgressRunner` for every external command (replaces adding spinners site by site):
- Where it lives: a `proc` module (`mod.rs` for the runner, `indicator.rs`, `parse.rs`) in the existing crate `crates/rtok-sys` (OS process shims), which both `rtok` and `crates/rtok-mcp` (T277) depend on, so the MCP crate needs neither the `rtok` crate nor its own `Command::new`; no new crate. It is the only place in the workspace that calls `std::process::Command::new`; `clippy.toml` gets `disallowed-methods = ["std::process::Command::new"]` with an `#[allow]` only inside `crates/rtok-sys`, so a new call site that bypasses the runner, in `rtok` or in `crates/`, fails `just check`. `TtyIndicator`'s `indicatif` dependency moves to that crate with its `toolchain.md` row. The Windows shim logic now in `spawn_cli` (`agents/mod.rs:246`) and `mcp_command` (`doctor.rs:932`) moves into the runner too.
- Interface:
  - `ProgressRunner::new(label: &str, program, args)` returns a builder with `.cwd()`, `.env()`, `.stdin()`, `.timeout()`, `.progress(Progress)`, `.output(Output)`, then `.run() -> Result<Captured>` or `.spawn() -> Result<Running>`.
  - `enum Output { Capture, Stream, Inherit, Detached }`. `Capture` keeps stdout/stderr and shows an indicator until exit; `Stream` forwards lines live and suspends the indicator around each write; `Inherit` hands the terminal to the child (`tspin`, `ketch` upgrade) with no indicator; `Detached` is for background children (demon, otel flush, deferred hook) with no indicator.
  - `enum Progress { Unknown, Parse(Box<dyn ProgressParser>), Callback }`. `Unknown` draws a spinner. The other two draw a progress bar as soon as the first measurable update arrives and fall back to the spinner until then.
  - `trait ProgressParser: Send { fn feed(&mut self, stream: Stream, line: &str) -> Option<Update>; }` with `struct Update { pos: u64, total: Option<u64>, unit: Unit, msg: Option<String> }` and `enum Unit { Bytes, Percent, Items }`. Bytes render as MB with speed and ETA, percent as a 0-100 bar, items as `pos/total`.
  - `trait Indicator { fn set(&self, u: &Update); fn message(&self, m: &str); fn suspend<R>(&self, f: impl FnOnce() -> R) -> R; fn finish(&self); }` with two implementations: `TtyIndicator` (indicatif, on stderr) and `Hidden`. The runner picks `Hidden` when stderr is not a TTY, under `--json`, inside MCP and hook contexts, and when `RTOK_NO_PROGRESS=1`, so their output stays byte-identical.
- How a process reports progress:
  - Parsing: the runner reads stderr and stdout line by line (splitting on `\r` too, which is how `git`, `curl` and `cargo` redraw) and passes each line to the parser. Built-in parsers in `parse.rs` are `GitProgress` (`Receiving objects: 45% (120/267)`, `Updating files`), `Percent` (any `NN%`), `CargoProgress` (`Building [==> ] 120/300`), and `JsonLines` (a line `{"progress":{"pos":..,"total":..,"unit":".."}}`, for rtok's own child processes and plugin CLIs). The runner adds the flag that turns progress on where the tool has one (`git --progress`).
  - Callback: for work rtok measures itself, `ProgressRunner::batch(label, total, Unit::Items)` returns a `Batch` whose `.step(msg)` moves the bar; each command inside the batch runs with its own spinner line under the bar. `rtok bench` (runs), `rtok doctor` (servers), `rtok worktree gc/clean` (worktrees) and `rtok agents install/update/remove` over several hosts use this.
  - Prompts: `proc::suspend(|| ask(...))` hides every live indicator while rtok waits for an answer and redraws it after. This replaces the T81 rule that turned the loader off for the whole interactive run.
- Result: a new host, plugin CLI or subcommand that runs a program through `ProgressRunner` gets the right indicator with no extra code; the point fixes 1-5 above become one-line `label` / `progress` / `output` choices.

Migration plan:
1. Add the `proc` module in `crates/rtok-sys` with the runner, both indicators, the four parsers and `Batch`; unit tests feed recorded `git` / `cargo` / `curl` stderr into the parsers, and a test asserts `Hidden` writes nothing.
2. Move the shared helpers first: `worktree/git.rs::git`, `agents/mod.rs::run_cli` / `spawn_cli` / `app_version`, `doctor.rs::spawn_mcp` / `mcp_command`. That converts most user-facing sites at once.
3. Convert the remaining sites file by file, choosing `Output` per the audit: `agents/restart.rs` (10, `Capture`), `bench.rs` (3, `Batch` plus `Capture`), `plugins/cmd/run.rs` (`Stream` or `Capture` per step 1), `graph/lsp.rs` and `graph/mod.rs` (`Capture`, long-lived LSP children via `.spawn()`), `graph/watch.rs` (`Capture`, hidden in the watcher), `demon.rs` (`Detached` for children, `Inherit` for upgrade, `Capture` for `sysctl`), `log.rs` (`Inherit`), `otel/export.rs`, `hooks/mod.rs`, `bin/rtok-hook.rs`, `mcp/wrap.rs` (`Detached` / `Stream`, always hidden). `build.rs` stays on `Command` (build scripts cannot use the crate) and is the one listed exception.
4. Delete the point indicators: `with_loader` and the T81 `interactive` switch in `cli.rs`, and `render::loader` / `render::spinner` once `graph index` uses a `Batch` over files. `render.rs` keeps only styles.
5. Turn on the `clippy.toml` ban and fix whatever it still finds, including `crates/`.
6. Update `docs/` (contributor notes: "run external programs only through `proc::ProgressRunner`") and `CHANGELOG.md`.

Fix:
1. Confirm the audit list above with `rg` over the whole workspace (including `crates/`), and record each site as covered / add / not needed in a table in this card.
2. Implement the `ProgressRunner` migration above; items 1-5 are covered by it, not by separate spinners. Every message names the action and the target in present tense and ends with `…`; the loader is cleared (`finish_and_clear`) before the command's own output or any error is printed.
3. Interactive rule (T81) stays: a loader never runs while rtok is waiting for an answer. Put the pause and resume in one helper so every prompt uses it.
4. Nothing changes when stderr is not a TTY: pipes, CI, `--json` and MCP output stay byte-identical.
5. Progress bar over spinner: when an operation can report progress (megabytes, percent, files or items done out of a known total), show an `indicatif` progress bar with that count, not a spinner. A spinner is only for a wait that cannot be measured, where the command gives no intermediate data until its output arrives. For the items above this means: `rtok bench` shows a bar over runs (`3/10`), `rtok doctor` a bar over servers when it probes more than one, `rtok worktree gc/clean` a bar over worktrees removed; a single `claude -p` run, one MCP server start or one `git worktree add` stays a spinner.

Check: on a TTY, each of items 1-5 shows its message from the moment the process starts until output appears; `rtok agents update claude` in a terminal shows step loaders and the plugin question is readable; `rtok doctor 2>/dev/null` and `--json` output are unchanged; a Vfs or snapshot test asserts no spinner bytes on non-TTY stderr; every operation with a known total shows a progress bar with its count and a spinner appears only on unmeasurable waits; `just check`.

Execution plan (after T275, T277 and T279, which touch the same `agents` spawn helpers):
1. Worktree `_worktrees/rtok-T276`. Confirm the audit with `rg` over the workspace and write the site table into this card (Fix 1).
2. PR 1: the `proc` module in `crates/rtok-sys` with `ProgressRunner`, `TtyIndicator` / `Hidden`, the four parsers, `Batch`, `proc::suspend`; parser tests on recorded stderr and a no-bytes test for `Hidden`.
3. PR 2: the shared helpers (`worktree/git.rs::git`, `run_cli` / `spawn_cli` / `app_version`, `spawn_mcp` / `mcp_command`), with `proc::suspend` replacing the T81 switch.
4. PRs 3-4: the remaining sites file by file, per the audit's `Output` choice.
5. PR 5: delete `with_loader` / `render::loader` / `render::spinner`, turn on the `clippy.toml` ban, contributor docs and `CHANGELOG.md`.
Each PR listed here becomes a subtask (`Tn.m`, D16) when it is claimed; this card stays the epic.

### T277. Move rtok's MCP core into its own crate `crates/rtok-mcp`

Problem: install, update, remove, status and ping of rtok's MCP entry are written separately in each host (`register_mcp` / `unregister_mcp` / `installed` in 20+ `src/agents/<host>/mod.rs`, plus `plugin_is_mcp`, `code_serves_mcp`, `installed_mcp_only` and the MCP half of `d21_plugin_apply`). That is how the Claude Desktop entry removal (T275) was repeated on Cursor, Codex, VS Code, ZCode, Kimi, Grok, Copilot and Gemini. T275's architecture principle states the rule; this task makes it a crate boundary so a host cannot grow its own MCP logic again.

Crate layout (`crates/rtok-mcp`, workspace member, not published, no dependency on the `rtok` crate; it depends on `rtok-sys` for T276's `ProgressRunner`):
- `spec.rs`: `McpSpec` (the per-host data from T275): config path(s) per surface, format (`Json`, `Jsonc`, `Toml`), key path (`mcpServers`, `servers`, `context_servers`, `mcp.servers`, `mcp_servers`), server name, command form (bare or absolute), entry shape (`type`, `args`, `env`), duplicate-name behaviour (`Merges`, `OverridesByScope`, `Errors`, `ShowsBoth`), plugin-served name, headless ping command. `Surface { Cli, Desktop, Ide }`.
- `registry.rs`: the one list of rtok's MCP servers (today `rtok`, plus graph or plugin servers when they get their own entry): name, command, args, env. Hosts never build an entry by hand.
- `config.rs`: read and write the host config through a `Fs` trait (so the existing `Vfs` tests plug in): `write_entry`, `remove_entry`, `read_entry`, keeping unrelated keys, comments in JSONC and TOML formatting, with a backup like today.
- `status.rs`: `McpStatus { surface, entry: Present | Missing | Stale(diff), plugin_serves: Option<name> }`; the truth source for T278.
- `ping.rs`: T275.1's ping: headless run through the spec's command, or spawn the configured server and call the `ping` tool.
- `doctor.rs`: the per-host MCP check `rtok doctor` runs (entry present, command resolves, server starts, `tools/list` answers).
- `ops.rs`: `apply(spec, Mode::{Install, Update, Remove}, fs) -> Report`, the only entry point hosts call.

What moves: from `src/agents/mod.rs` the MCP read/write helpers, `installed_mcp_only`, the MCP half of `d21_plugin_apply`; from every host its `register_mcp` / `unregister_mcp` and the MCP branch of `installed`; `plugin_is_mcp` (cursor) and `code_serves_mcp` (claude); from `src/doctor.rs` the MCP probe (`spawn_mcp`, `mcp_command`, using T276's `ProgressRunner` from `crates/rtok-sys` for the spawn, so the crate never calls `Command::new` itself); the `ping` tool body for T275.1. What stays in `rtok`: hooks, proxy, plugin install via host CLIs, desktop restart, and each host's `McpSpec` value.

Migration by host:
1. Create the crate with `spec`, `registry`, `config`, `status`, `ops` and a table-driven `Vfs` test over sample specs. No host uses it yet.
2. Claude (Code and Desktop) first, because it is where T275 was found: implement its `McpSpec`, route `apply` and `installed` through `rtok_mcp::ops` / `status`, delete `code_serves_mcp`. Land with T275's fix and T278's status.
3. The hosts T275 lists as affected: Cursor, Copilot, Gemini, Codex, VS Code and Insiders, ZCode, Kimi, Grok. One commit per host, each deleting that host's MCP functions.
4. The unaffected hosts: Kilo, OpenCode, Cline, Devin, Windsurf, Zed, MiMo, CodeWhale, omp, pi, Antigravity.
5. Move `doctor`'s MCP probe and add `ping` (T275.1) on top of the crate.
6. Delete the now-empty helpers in `src/agents/mod.rs`, add a test that fails when a file under `src/agents/` touches an MCP key directly, generate the host table in `docs/agents.md` from the specs.

Check: every host's install, update, remove, status and ping pass the same table-driven test; `rg 'mcpServers|context_servers|mcp_servers' src/agents` finds only `McpSpec` values; `just check`.

Execution plan (after T275 PR A, which gives the core in `src/agents/mcp.rs`):
1. Worktree `_worktrees/rtok-T277`. PR 1: crate `crates/rtok-mcp` with `spec`, `registry`, `config` (behind an `Fs` trait the `Vfs` implements), `status`, `ops`, and the table-driven test over sample specs; `toolchain.md` row.
2. PR 2: Claude moves onto the crate; `src/agents/mcp.rs` shrinks to spec rows.
3. PRs 3-4: the other hosts, one commit per host, at most 10 files per PR.
4. PR 5: `doctor`'s MCP probe into the crate; `ping` joins when T275.1 lands.
5. PR 6: delete leftover helpers, add the guard test against MCP keys under `src/agents/`, generate the `docs/agents.md` host table.
Each PR listed here becomes a subtask (`Tn.m`, D16) when it is claimed; this card stays the epic.

### T278. `rtok agents info <agent>` reports the real MCP state, not "mcp installed" by assumption

Problem: `agents info claude` shows `mcp installed` for Claude Desktop with no `rtok` entry in `claude_desktop_config.json`, because `installed` (`claude/mod.rs:688-694`) returns `mcp` when the Code plugin is present (`code_serves_mcp`), and matches the text `"rtok"` anywhere in the file. That hid today's bug. Other hosts do the same when a plugin is installed (`files.contains("mcp") || plugin`).

Fix:
1. Status comes from `rtok_mcp::status` (T277), or from an equivalent function in `src/agents/mod.rs` if T278 lands first. It parses the config and looks up the exact key path and server name; no substring match.
2. `agents info` prints MCP per surface on its own line, for example `mcp  desktop  missing (claude_desktop_config.json has no "rtok")`, `mcp  cli  plugin rtok@rtok (serves "rtok")`, `mcp  cli  entry ~/.claude.json`. A stale entry (wrong command or args) prints `stale` and the difference. Hooks keep their current line.
3. A plugin only counts for the surface it actually serves. The Claude Code plugin never makes Desktop show as installed.
4. `--json` and the web UI model (`web/model.rs`) carry the same fields: `surface`, `entry` (`present`, `missing`, `stale`), `plugin`.
5. `agents list` and `installed_hosts` use the same status, so "installed" means the same thing everywhere.

Tests: a table-driven `Vfs` test per host writes each combination (no entry, entry, stale entry, plugin only, entry and plugin) and asserts the printed and `--json` status matches the file content; a regression test for today's case (Code plugin installed, Desktop file without `rtok`) expects `desktop missing`.

Check: on the creator's machine, before T275 is fixed `rtok agents info claude` shows Desktop `missing`; after `rtok agents install claude` it shows `present`; `just check`.

Execution (2026-09-27): one PR off main. Status comes from `rtok_mcp::status` (T277, merged in #437) per surface. `agents info`, `agents list`, `installed_hosts` and `web/model.rs` all read it, so a plugin only counts for the surface it serves. Tests: one table-driven `Vfs` test per host, plus the regression "Code plugin installed, Desktop file without `rtok`" → `desktop missing`. The T275 per-host PRs still open (VS Code, ZCode, Kimi, Grok) touch the same host modules, and whichever lands second rebases.
Progress (2026-09-28): merged in #464 (per-surface `mcp` lines, `--json` and web `mcp: [{surface, file, entry, diff?, plugin}]`, table-driven test over every host, the `desktop missing` regression). Left: the creator-machine Check.

### T279. One plugin version scheme for every install source (GitHub, local, marketplace), and `agents update` that skips an up-to-date plugin

Problem: every plugin manifest is still `0.0.1` while rtok is at `0.10.0` (tag `v0.10.0`): `plugins/claude/.claude-plugin/plugin.json` (`rtok@rtok`), `plugins/codex/.codex-plugin/plugin.json`, `plugins/cursor/plugin.json` and `.cursor-plugin/plugin.json`, `plugins/copilot/plugin.json`, `plugins/gemini/gemini-extension.json`, `plugins/kimi/kimi.plugin.json`, `plugins/pi/package.json`. Claude Code caches a plugin by its manifest version (`~/.claude/plugins/cache/rtok/rtok/0.0.1/`, `installed_plugins.json` records `"version": "0.0.1"` for commit `12c7e91`), so a new build with the same number is not a new version to it. rtok itself has no way to tell which plugin build is installed or where it came from, so `agents update` either reinstalls every time or trusts the host. A plugin reaches a user from three sources, and the scheme has to work for all of them:
- GitHub: the host installs straight from the repo (`claude plugin marketplace add pyrlyn/rtok`, Gemini `extensions install https://github.com/pyrlyn/rtok`, similar for others), at a branch or tag.
- Local: installed from the plugin tree of an rtok checkout or install (`plugins/<host>`; Claude via `claude plugin marketplace add <path>`, the pre-T139 flow), used for development and offline installs.
- Marketplace: the host's own catalog entry, which updates when a push to the repo changes the committed catalog (`.claude-plugin/marketplace.json`, `.agents/plugins/marketplace.json`, T183's `marketplace.yml` verifies them).

1. Version file: format and location.
   - Each plugin tree carries `plugins/<host>/.rtok-plugin-version`, committed, one JSON object: `{"schema":1,"plugin":"claude","version":"0.10.0"}`. `version` is SemVer and equals the rtok version at the commit. It sits at the plugin root, so every source copies it with the plugin: the GitHub and marketplace installs land it in the host's install dir (for Claude `installPath` from `installed_plugins.json`), a local install copies or links it.
   - Local installs from a git checkout add build metadata when rtok writes the installed copy: `"version":"0.10.0+g12c7e91"`, and `+g12c7e91.dirty` for uncommitted changes, plus `"source":"local"`. rtok writes the same string into the installed copy's manifest `version`, so hosts that cache by version (Claude) also see a new build.
   - rtok keeps an install receipt per host at `$XDG_STATE_HOME/rtok/plugins.json` (`~/Library/Application Support/rtok/plugins.json` on macOS, `%LOCALAPPDATA%\rtok\plugins.json` on Windows): `{"claude":{"source":"github","ref":"v0.10.0","marketplace":"rtok","path":"<installPath>","version":"0.10.0","installed_at":"…"}}`. For a local install `ref` is the checkout path and commit; for a marketplace install it is the marketplace name and its source.
2. How update finds the source and the new version.
   - Source: the receipt first. With no receipt, the host's own records: for Claude, `known_marketplaces.json` (`"source":"github","repo":"pyrlyn/rtok"` means GitHub or marketplace, a `directory` source means local) and `installed_plugins.json` (`installPath`, `version`, `gitCommitSha`); each host's lookup is a field in its plugin spec, next to T275's `McpSpec`, not a code branch.
   - New version, by source. GitHub: read `plugins/<host>/.rtok-plugin-version` at the tag that matches the running binary (`v{CARGO_PKG_VERSION}`), so plugin and binary stay in step; with `--channel main` read it from `main`. Local: read the file in the local plugin tree and add `+g<sha>[.dirty]` from `git describe`. Marketplace: refresh the host catalog first (`claude plugin marketplace update rtok` and the equivalents), then read the file inside the refreshed catalog checkout (for Claude `~/.claude/plugins/marketplaces/rtok/plugins/claude/.rtok-plugin-version`).
   - Installed version: the `.rtok-plugin-version` inside the installed copy, falling back to the receipt, then the host record's `version`.
3. Comparison and decision.
   - Parse both as SemVer. Equal version and equal build metadata: skip, print `plugin rtok@rtok 0.10.0 up to date (github)`. New is greater: update in place through the host (`claude plugin update`, T242.3's reinstall fallback), then rewrite the receipt. New is lower: skip with a warning naming both versions; `--force` (step 6) reinstalls anyway.
   - Same base version but different build metadata (local builds): update, since SemVer ignores metadata and the build did change.
   - A source change (the receipt says local, the user now installs from GitHub, or T139's stale marketplace) is always an update: reinstall from the new source.
   - `--force` bypasses this table entirely (step 6); `--dry-run` prints the decision for each host without acting. The decision table is one pure function with unit tests.
4. Installs without a version file (everything before this task).
   - The installed version comes from the host record if it has one (`0.0.1` today), otherwise it is treated as `0.0.0`. Either is lower than any real release, so the first `agents update` after this task updates once, writes the version file into the new copy and creates the receipt; from then on the normal rule applies.
   - If that update fails, the old plugin stays, the receipt is not written, and the report says `plugin rtok@rtok: legacy install, update failed: …` so the next run tries again.
   - `agents info` shows `legacy (no version file)` for such installs until then (ties into T278).
5. Release-plz and bump.
   - `tools/plugin-versions.sh --set <version>` writes the version into every `.rtok-plugin-version` and every manifest `version` above; `--check <version>` lists each file that differs and exits non-zero.
   - `tools/release.sh` (the version commit `bump.yml` makes) calls `--set` in the same commit that edits `Cargo.toml` and `Cargo.lock`, and the version files join the list of files that commit may touch. `release-plz` only edits Cargo files and today releases only `rtok-plugin-sdk`, so the plugin files never go through it; if `release-plz` ever opens the rtok release PR, that PR runs `--set` in a follow-up step, and the check below blocks it until it does.
   - CI: `ci.yml` runs `--check` against the `Cargo.toml` version on every PR; `release.yml` runs `--check` against the tag (`v` stripped) before building and fails the release on a mismatch. A Rust test asserts every version file and manifest equals `env!("CARGO_PKG_VERSION")`, so `just check` catches drift locally.
   - Raise every manifest and add every version file at `0.10.0` in this task's PR.

6. `--force`: unconditional reinstall.
   - Syntax: `rtok agents update <agent>[,<agent>…] --force`, and `rtok agents update --force` with no host for every host rtok is installed in (the existing "host omitted" rule; `--all` already means "all variants, CLI and desktop" in `UpdateArgs`, so it keeps that meaning and does not also mean "all hosts"). Combines with `--cli` / `--desktop`, `--no-restart` and `--dry-run`.
   - Behaviour: no version lookup, no comparison, no skip. For each selected host rtok removes the installed plugin through the host (`claude plugin uninstall rtok@rtok` and the equivalents, plus a stale marketplace entry as in T139), then installs it again from the source: the receipt's source, else the one step 2 detects, else the default for that host (GitHub at `v{CARGO_PKG_VERSION}`). `--force --source github|local|marketplace` picks the source explicitly and replaces the recorded one.
   - Plugin not installed: `--force` installs it from scratch (the uninstall step is skipped, not an error), then writes the version file and receipt like a normal install.
   - Version file and receipt: after a successful reinstall rtok reads `.rtok-plugin-version` from the new installed copy (writing the `+g<sha>` form for a local source) and rewrites the receipt with the new source, ref, path, version and time. A downgrade or an identical version is reinstalled all the same.
   - Failure: if the uninstall succeeds and the install fails, the report says so plainly (`plugin rtok@rtok removed, reinstall failed: …`), the receipt is deleted so the next `agents update` treats the host as "not installed" and installs, and the exit code is non-zero. MCP entries and hooks follow T275 (always rewritten), not the plugin step.
   - `--dry-run --force` prints `would reinstall rtok@rtok from github v0.10.0 (forced)` per host and changes nothing.
   - Tests (with a fake host CLI on `PATH` that logs its calls, plus `Vfs`): equal versions with `--force` still run uninstall then install; without `--force` they run nothing; a not-installed host with `--force` runs install only and writes the receipt; a newer installed version with `--force` is replaced by the older source version; `--force --source local` switches the receipt source; install failure after uninstall deletes the receipt and exits non-zero; `--force` with no host touches every installed host and only those; `--dry-run --force` calls no CLI and writes no file; the forced path never calls the version comparison function.

7. Documentation (required; T279 and T279.1 are not done without it).
   - A new page `docs/plugin-versions.md`, written for a developer who has not read the code. Sections, in this order:
     1. Why: hosts cache plugins by version, three install sources, what went wrong with `0.0.1`.
     2. The version file: location `plugins/<host>/.rtok-plugin-version`, the JSON fields (`schema`, `plugin`, `version`, `source`), who writes it (committed by the release, rewritten with `+g<sha>[.dirty]` for local installs), where it ends up after install for each host.
     3. The receipt `plugins.json`: path per OS, every field, when it is written, rewritten and deleted.
     4. Sources: GitHub, local, marketplace; how each is detected (receipt, then host records) and where the new version is read from for each, with the Claude paths as the worked example.
     5. The decision: a table of every case (equal, newer, older, same base with different build metadata, source change, legacy without a file) and its result (skip, update, reinstall, warn), then `--force`, `--source` and `--dry-run`, and what happens when a reinstall fails.
     6. `rtok agents outdated` and `rtok agents update --check`: what is listed and what is hidden, the two "nothing to do" messages, the `--json` schema, `--exit-code`, why it works offline.
     7. Releasing: `tools/plugin-versions.sh --set` / `--check`, where `tools/release.sh` and `bump.yml` call it, the CI and `release.yml` checks, why `release-plz` does not touch these files and what happens if it ever opens the rtok release PR.
     8. Troubleshooting: "plugin stays old after update", "legacy install", "version mismatch in CI", each with the command to run.
   - Each section has a short command example with real output copied from a run, not invented.
   - Links: README gets a line under the plugin/agents section, `Plugin versions and updates: [docs/plugin-versions.md](docs/plugin-versions.md)`; `docs/release.md` links the Releasing section; `docs/agents.md` links it next to `agents update`; `CHANGELOG.md` mentions the page. The landing site picks the page up through the existing docs sync (owned separately; this task does not edit `sync-docs.yml`).
8. Tests (required; the full set for T279 and T279.1).
   - Unit tests on the pure decision function (no files, no processes), one case each: equal versions skip; newer available updates; older available skips with a warning; same base with different `+g<sha>` updates; same base with `.dirty` against clean updates; identical build metadata skips; source change reinstalls; legacy (no version) against any release updates; `--force` returns reinstall for every one of these inputs; invalid SemVer in a version file is an error that names the file.
   - Unit tests on the version file and receipt: parse and write round-trip; unknown `schema` rejected; receipt paths per OS; local version string from a clean and a dirty `git describe`.
   - Integration tests in `tests/plugin_versions.rs` with `Vfs` fixtures and a fake host CLI on `PATH` that logs every call:
     - update: equal versions run no CLI; a newer version runs `plugin update` and rewrites the receipt; an older one runs nothing and warns.
     - force: equal versions still run uninstall then install; a not-installed host runs install only and writes the receipt; a newer installed plugin is replaced by the older source version; the forced path never calls the comparison.
     - source change: `--force --source local` over a GitHub receipt reinstalls from the local tree and switches the receipt source; a stale marketplace (T139) triggers reinstall.
     - failure: the fake CLI fails install after a successful uninstall; the receipt is deleted, the report says `removed, reinstall failed`, the exit code is non-zero, and the next `update` installs.
     - dry-run: `--dry-run` and `--dry-run --force` spawn no CLI and change no file; their printed decisions match what a real run then does.
     - legacy: an install without a version file and with host record `0.0.1` updates once, then the second run skips.
     - outdated: only outdated rows are printed; all current prints the up-to-date line; nothing installed prints `no rtok plugins installed`; legacy rows show `legacy`; newer and same-version-with-metadata rows are hidden; `--json` matches the schema in T279.1; `--exit-code` returns 10 when something is outdated and 0 otherwise; `agents update --check` output is byte-identical to `agents outdated`.
     - offline: `agents outdated` runs with the network disabled (proxy env pointed at a closed port) and the fake CLI logs no call.
   - Release checks: `plugin-versions.sh --check` passes on the tree and fails after one manifest or version file is edited (a shell test in `tools/`); the Rust test that compares every manifest and version file with `CARGO_PKG_VERSION`.
   - All of it runs in `just check` and CI on macOS, Linux and Windows.

Check: `rtok agents update claude` on today's install (0.0.1, no file) updates once to `0.10.0` and writes the receipt; a second run prints `up to date` and runs no `claude` command; a local install from a dirty checkout shows `0.10.0+g<sha>.dirty` and a new commit triggers an update; `--dry-run` lists the decision per host; editing one version file to `0.0.2` fails the CI check and the test; `rtok agents update claude --force` reinstalls even when up to date and rewrites the receipt; `just check`.

Execution plan:
1. Worktree `_worktrees/rtok-T279`. PR 1: `.rtok-plugin-version` files and every manifest at `0.10.0`, `tools/plugin-versions.sh --set/--check` with its shell test, the `CARGO_PKG_VERSION` Rust test, `--check` in `ci.yml`, `release.yml`, `tools/release.sh`.
2. PR 2: version file and receipt types, the installed/new version lookup, and the pure decision function with its unit tests (step 8, first two groups). No behaviour change yet.
3. PR 3: `agents update` uses the decision; `--force`, `--dry-run`, `--source`, the legacy path, failure handling; integration tests in `tests/plugin_versions.rs` with a fake host CLI.
4. PR 4: `docs/plugin-versions.md` with real command output, and its links (step 7).
Each PR listed here becomes a subtask (`Tn.m`, D16) when it is claimed; this card stays the epic.
Progress (2026-09-28): PRs 1-4 merged (docs: #465, today's behaviour). Open against this card, for the creator: the installed copy's `.rtok-plugin-version` is never read or written (`read_installed` and `VersionFile::write` are unused), so there is no legacy line from it; a missing `claude` on `PATH` prints "already current"; the dry-run reinstall wording differs from step 5; `--source local` fails from a release install; the marketplace source does not read the catalog. Only Claude is wired; Codex, Copilot and Gemini follow.


### T281. Probe: tie a host session's hooks and its rtok MCP server to one agent

Creator request 2026-09-27 (T281–T290, D34): every agent working through rtok gets one rtok agent id, visible to the user in the terminal and to the agent over MCP. Hooks see the host's session id (`research.md` §26), but `rtok mcp` is started by the host with only `["mcp"]` and its cwd (`src/mcp.rs:592`, host from `[hook] host`); nothing today tells the MCP process which session it serves. Without that link an agent's MCP calls (`whoami`, `worktree_add`, `agent_send`) cannot be attributed to its agent id. No product code in this task.

Plan:
1. For each host with hooks and MCP (Claude Code CLI and desktop, Cursor, Codex, Copilot CLI, Grok, Gemini, Kimi, ZCode, CodeWhale): a throwaway MCP wrapper and hook script in the scratchpad (never committed) log, per process: pid, ppid chain up to the host process, cwd, every env var whose name contains `SESSION`, `CONVERSATION`, `THREAD` or the host name, MCP `initialize.params.clientInfo` and `_meta`, and the hook payload's session field.
2. Run one session per host by hand, on the creator's machine only; real agents are for manual debugging, never tests (T280). Include one sub-agent per host that has them.
3. Pick the link rule per host, in this order of preference: a session id env var the MCP process inherits (e.g. `GROK_SESSION_ID`, `GEMINI_SESSION_ID`); the nearest common host ancestor pid shared by hook processes and the MCP process; cwd + host + start time as the last resort, marked ambiguous when two sessions share a cwd.
4. Check whether one MCP process serves several sessions (the desktop apps may share one) and whether it outlives its session.

Check: `research.md` §26 gains a dated table host → host version → the MCP process's link to the session (env var name / ppid rule / cwd only / none) → sub-agent behaviour → one MCP process per session yes/no, each row with its source (the probe log or the vendor docs URL). The T283 card is updated with the rule per host.

Execution (2026-09-27): agent sessions may not read their own process environment (blocked by the host's policy), so the live runs are the creator's. (1) Worktree `_worktrees/rtok-t281`. (2) Vendor docs first, per host: which env vars an MCP server process inherits, the hook payload's session field, whether one MCP process serves one session; each fact cited with URL and date. (3) A probe kit in the scratchpad, never committed: an MCP stdio wrapper that logs pid, ppid chain, cwd, the filtered env var names and values, `initialize` params, then execs `rtok mcp`; a hook script that logs the same next to the payload's session field; a one-page run sheet for the creator. (4) `research.md` §26 gets the table from the docs now, with a "probe" column left `pending` until the creator's logs arrive; T283's card gets the rule per host once they do.

### T283. An agent learns its own rtok agent id

Depends on T282 and T281 (the MCP link rule per host) and on T275 (every host's MCP entry is rewritten there; do not collide). An agent must know its id to report it, to claim worktrees and to message others.

Plan:
1. Worktree `_worktrees/rtok-T283`.
2. SessionStart (and SubagentStart where the host has it, T262.2) adds one line to the injected context: `rtok agent id: <first 8 hex> (full: <uuid>). Use it with rtok's agent_* and worktree_* MCP tools.` Fixed wording, byte-stable apart from the id, counted in the injection budget; no line when `[agents] enabled = false`.
3. Env: where the host lets a SessionStart hook export variables to the agent's shell (Claude Code `CLAUDE_ENV_FILE`: confirm in the hooks docs first and cite it), export `RTOK_AGENT_ID`, so `rtok` run from the agent's Bash tool knows its caller. Other hosts: `rtok` resolves the caller by the T281 rule.
4. MCP: `rtok mcp` resolves its agent at `initialize` with the T281 rule for its host; for hosts without hooks (Zed, Antigravity, Cline, pi, omp, opencode, …) the MCP process registers its own agent row (host from a new `--host <id>` arg that every host's MCP entry passes; update each `register_mcp` call and the host tests after T275 lands). New MCP tool `whoami` → `{id, short, host, host_session, cwd, worktrees: [...]}`.
5. CLI `rtok agents whoami [--json]` → the same, from `RTOK_AGENT_ID` or the T281 rule; exit 1 with "not inside an agent session" otherwise.

Check: hook fixture test: SessionStart output carries the line and it is identical across two runs but for the id; MCP e2e with a fake client: `initialize` then `tools/call whoami` returns the registered id; a hook-less fake host registers through MCP alone; trycmd for `agents whoami`; `surface_parity`, `config_coverage`, man page; `just check`.

Execution (2026-09-27): two PRs. PR 1, cut on top of T282's branch until #439 merges: the SessionStart line (step 2) inside the injection budget; `RTOK_AGENT_ID` through `CLAUDE_ENV_FILE` (step 3, cited from the Claude Code hooks docs); `rtok agents whoami [--json]` from `RTOK_AGENT_ID` (step 5); tests: hook fixture byte-stable but for the id, `enabled = false` prints nothing, trycmd, `surface_parity`, `config_coverage`, man page. PR 2, after T281's rules and T275's per-host PRs land: `rtok mcp` resolves its agent at `initialize`, `--host <id>` in every host's MCP entry, hook-less hosts register through MCP, MCP tool `whoami`; MCP e2e with a fake client.
Progress (2026-09-28): PR 1 merged (#449): SessionStart line, `RTOK_AGENT_ID`, `rtok agents whoami` (host session id only in `--json`). Left: PR 2, the MCP link at `initialize` after T281's probe.
Progress (2026-10-03): PR 2 is split into T283.1 (resolve the link, MCP `whoami`, `rtok mcp --host`), T283.2 (`--host` in every host's MCP entry) and T283.3 (the ancestor-pid rule, which needs the hook wire request to carry a pid). The link rule is derived from `research.md` §26's vendor docs and spawn code; the T281 live probe only confirms it.
Each PR listed here becomes a subtask (`Tn.m`, D16) when it is claimed; this card stays the epic.

### T289. Worktrees the host creates join rtok: `rtok worktree adopt` and the post-create hooks

Depends on T285, T286. Only Claude Code can redirect worktree creation (T159). Cursor (`.cursor/worktrees.json` `setup-worktree*`), Kilo (`.kilo/setup-script`) and Devin/Windsurf (`post_setup_worktree`) only run a script after they create a worktree in their own pool (`research.md` §26). For worktrees to behave the same on every host, those must still get an owner, an agent id and rtok's remove / gc / clean.

Plan:
1. Worktree `_worktrees/rtok-T289`.
2. `rtok worktree adopt [<path>] [--task <id>] [--agent]` and MCP `worktree_adopt`: lock a host-made worktree with the v2 reason (T285), record the claim and `source: <host>`; the directory stays where the host put it. First confirm on each host whether a locked worktree breaks the host's own eviction (Cursor's cap of 25, Devin/Windsurf LRU); where it does, adopt records the claim in the store only, with no git lock, and the card says so.
3. Wire the post-create scripts: `rtok agents install <host> --project` writes rtok's entry into the project file (`.cursor/worktrees.json`, `.kilo/setup-script`, Devin/Windsurf's hook config), our entry only, the rest byte-for-byte (host-config rule); removal takes it out.
4. Hosts with native worktrees and no hook (Codex, Grok Build, MiMo, omp, Antigravity): the skill tells the agent to call `worktree_adopt` when it finds itself in a host-made worktree.
5. `rtok worktree list` already shows every registered worktree of the repo (git knows them wherever they are); add `source` (`rtok`, `claude`, `cursor`, …) from the path pool.

Check: adopt e2e in a scratch repo with a worktree under a fake `~/.cursor/worktrees/`; install/remove e2e per host writing only our entry; list shows `source`; `just check`.

Execution (2026-10-03, Claude Code / sonnet-5): split into four PRs, each at most about 300 LOC, stacked on T288 step 4. Design decisions the card left open:
- `adopt` is `claim` with three differences: the path defaults to the caller's worktree (cwd), `--task` names the task when the branch cannot (a detached HEAD such as Codex's `thread-N`; the directory name is the last fallback), and a worktree in a pool whose host evicts by itself (Cursor, Windsurf/Devin, Codex) gets **no git lock**, only the claim row. Reason: the card says to confirm per host whether a locked worktree breaks the host's eviction, and that needs a live run on each host (the creator's probe, like T281); until it is confirmed, a lock could stop Cursor's cap of 25 from evicting, so the safe choice is the store-only claim. `worktree list` and `gc` already read an unlocked worktree's claim row (T285), so a live agent's adopted worktree is still never collected. Flip the pool table once a host is confirmed.
- The origin of a worktree is derived from its path (`~/.cursor/worktrees/`, `~/.windsurf/worktrees/`, `<repo>/.claude/worktrees/`, `<repo>/.kilo/worktrees/`, `$CODEX_HOME/worktrees`, `~/conductor/workspaces/`; else `rtok` under `[worktree] root`, else `other`), so no migration. The `worktree list` table already has a `source` column (source bytes), so the new field is named `origin` in the table and in `--json`.

### T289.3. Post-create scripts: `rtok agents install <host> --project` for Cursor, Kilo and Devin/Windsurf

Done means: rtok's entry is written into `.cursor/worktrees.json` (`setup-worktree*`), `.kilo/setup-script` and Devin/Windsurf's `post_setup_worktree` hook config, our entry only and the rest of each file byte-for-byte (host-config rule), and removal takes it out; the entry runs `rtok worktree adopt`.

Check: install/remove e2e per host that changes only our entry.

Open questions (2026-10-03, Claude Code / sonnet-5; not started, ask the creator before coding):
1. A post-create script runs outside the session: no `RTOK_AGENT_ID`, no session id. `adopt` today refuses without an agent. Whom does it bind? Candidate: the one live agent of that host whose cwd is the repository (T283.1 rule, ambiguous binds nothing), else a claim with no agent that the next `worktree_adopt` or hook in that worktree completes.
2. The host-config formats must come from primary sources before any writer: the `.cursor/worktrees.json` shape (`setup-worktree*` values), where Devin/Windsurf read `post_setup_worktree` (project vs user `hooks.json`), and Kilo's `.kilo/setup-script` is a plain script, so "our entry only" needs a marked block. `research.md` §26 names the keys but not the exact file shapes.
3. Whether a git lock breaks a host's own eviction is still the T281 live probe; the scripts must go through `adopt`, which already skips the lock in evicting pools.


### T329. Graph page: project selector, auto-added projects and linked projects (epic)

Ivan, 2026-10-01: in the web UI's graph tab, the graph is built for a project the user picks. The page always shows which project is selected. Projects the user needs are added automatically. Other projects can be linked to the selected one, and the graph then traverses into them as if everything were one project. If the selected project references other projects, those are added, indexed and linked automatically, so an agent working in the current project can follow the graph across them right away.

Today the graph plugin (`src/plugins/graph/`) always works on one root: the process's current directory. The index is keyed by that root (`index::canon(root)` in `src/store/symbols.rs`), and the MCP tools `symbol`, `callers`, `impact`, `outline` and `explore`, plus `dead` and `affected`, only see that root. The graph page shows the same single root (`root .`). There is no way to pick another project and no way to follow a call into a dependency's source.

Split (2026-10-03, complexity 5): one subtask = one PR, T329.1 to T329.21 in dependency order (T329.1 to T329.3, T329.6, T329.12, T329.13, T329.14, T329.20, T329.22 and T329.23 are already in `done.md`). This card stays the specification; each subtask reads the section it names and updates `docs/` (en, ru, uk) for its own part. T337, which gated T329.11/T329.17, is settled (requests never re-probe; the health check does); T334, which gated T329.9, is settled (`tags` stays the default, `auto` is opt-in); T336, which gated T329.4, is settled (the cwd, not the web selection).

#### Terms

- **Project**: a directory rtok indexes as one unit, identified by its canonical root path. Display name defaults to the directory name (or the package name from the manifest when there is one); the user can rename it.
- **Selected project**: the project the graph page answers for. The CLI and MCP tools do not follow it: without `project` they answer for the caller's current directory and its links (T336).
- **Link**: a directed edge "project A sees into project B". A link is either **manual** (the user made it) or **auto** (rtok made it from a reference, see 4).
- **Graph scope**: the selected project plus every project reachable through its links (transitively). All graph queries run over the scope.

#### 1. Project registry

- A `projects` table in the rtok store holds id, canonical root, display name, origin (`manual`, `session`, `worktree`, `mcp`, `reference`), created and last-used times, and per-project index status (rows, files, pending, `indexed_at`, watch state, last error).
- Each project keeps its own symbol index, keyed by its canonical root as today, so switching projects never re-indexes the others and never mixes their rows.
- Two paths that canonicalize to the same directory (symlinks, `..`, case on macOS) are the same project; registering one twice is a no-op that only updates last-used.
- A project whose root no longer exists stays in the registry marked **missing**: it is greyed out in the selector, excluded from the scope, and its links are kept so they come back if the directory returns. The user can remove it.
- Removing a project drops rtok's index rows, its links in both directions and its registry row. It never touches the project's files.
- The registry and links migrate forward with the store schema; an existing store starts with one project, the root it already indexed, selected.

#### 2. Project selector on the graph page

- The page header has a project selector listing every known project: name, root path, index status and a link count. It is searchable when there are more than about ten projects.
- Picking a project switches the whole page to that project's scope: summary counts, dead symbols, the symbol/callers/impact views and the graph drawing.
- The selection is stored in the rtok store, so it survives page reloads, other browser tabs (they update over `/ws`) and `rtok web` restarts.
- When `rtok web` starts in a directory that is a known project and nothing is selected yet, that project is selected. A stored selection that is now missing falls back to the current directory's project, with a notice.

#### 3. Current-project indicator

- The selected project's name and root path are always visible in the page header, together with its index status: rows, files, pending files, last indexed time and watch state.
- When the scope includes linked projects, the header says so ("+ 3 linked") and expands to list them, each with its own status.
- States the page must show clearly: **not indexed yet** (empty state with an "Index now" action), **indexing** (progress, the page stays usable on the old data), **stale** (pending files, same banner the tools already use), **failed** (the error and a retry action), **missing** (root gone).

#### 4. Automatic adding

Projects are added to the registry, without the user asking, in two ways.

**4a. Projects rtok sees in use.** The working directory of a hooked agent session, a worktree created or adopted through `rtok worktree` (T285, T289), and the root of any graph MCP call are registered when first seen. A worktree is registered as its own project (its files differ from the main checkout) with its display name showing the branch.

**4b. Projects the selected project references.** When a project is indexed, rtok reads its manifests and collects references to code that lives outside its root but on this machine. Each referenced directory is registered as a project (origin `reference`), indexed, and auto-linked from the referencing project. Reference sources, in this order:

- Cargo: `path = "..."` dependencies and `[patch]` entries, and workspace members outside the root.
- npm/pnpm/yarn: `file:`, `link:` and `workspace:` dependencies that resolve outside the root.
- Go: `replace` directives with a local path in `go.mod`, and `go.work` `use` entries.
- Python: path dependencies in `pyproject.toml` (`{ path = "..." }`, editable installs).
- Git submodules (`.gitmodules`) whose checkout is present.
- Anything else the indexer finds while resolving imports: an import that resolves to a file outside the root (through an LSP server or the language's resolver) adds that file's project root (nearest directory with a manifest or `.git`).

Rules for 4b:

- References are followed transitively: if B (referenced by A) references C, C is added, indexed and linked from B, so A's scope includes C. A depth limit (`[plugins.graph] reference_depth`, default 3) and a project cap (`max_auto_projects`, default 20) stop runaway chains; hitting either is shown on the page and logged, never silent.
- Registry dependencies that are not local source (crates.io, npm registry, PyPI, Go module cache) are not followed by default, so the scope stays the user's own code. An opt-in setting (`include_registry_deps = false`) can add them later; it is out of scope for the first PR.
- A reference to a path that does not exist is recorded on the referencing project as a warning ("references ../foo, not found") and nothing is added.
- Auto-indexing runs in the background with the existing index code; the selected project is usable while its references are still indexing, and results from a reference that is not indexed yet are marked incomplete rather than missing.
- Re-indexing a project re-reads its manifests: a new reference adds and links a project; a removed reference removes the auto link (the project stays in the registry until the user removes it). Manual links are never removed automatically.
- If the user unlinks an auto link, rtok remembers that and does not re-create it on the next index.

**Turning it off.** `[plugins.graph] auto_add_projects = true` controls 4a and `auto_link_references = true` controls 4b, both on by default, documented in `docs/config.md`. With both off, the registry changes only through the page and the CLI.

#### 5. Manual links

- From the selected project the user can link any known project and unlink any linked one; the page lists links with their kind (manual or auto) and the reason for auto links (for example "Cargo path dependency `../ketch-core`").
- Linking a project that is not indexed yet starts indexing it.
- Links are directional: linking B into A puts B in A's scope, not A in B's. The page offers "link both ways" as a shortcut that creates two links.
- Cycles are allowed (A to B to A). Scope building visits each project once, so cycles never loop or duplicate rows.
- A project cannot link to itself, and linking an already-linked project is a no-op.

#### 6. Cross-project traversal

- Every graph query runs over the scope as one graph: `symbol`, `callers`, `impact` (with `depth` and `to`), `explore`, `affected` and `dead`.
- A reference from a call site in one project to a definition in a linked project resolves and is followed, in both directions: `callers` of a function in B include call sites in A when A links B, and `impact` from a change in B walks up into A.
- Each result row shows which project it belongs to (project name badge on the page, a `project` field in JSON, a `[name]` prefix in text output).
- Ambiguity: when the same symbol name is defined in several projects in the scope, results are grouped by project and marked ambiguous, using the same banner the single-project path already uses. A definition in the selected project ranks first.
- `dead` is computed over the scope: a symbol in B used only from A is not dead while A links B. Dead symbols are still reported per project.
- `affected` with git changes reads `git diff` in every project in the scope that is a git repo, and maps test commands per project.
- Output caps and token budgets apply to the whole scoped answer, not per project, so linking projects does not multiply the size of an MCP reply.
- Watching: when `watch` is on, file changes in any project in the scope update its index and refresh the page over `/ws`.

#### 6a. Backends: `tags` by default; `auto` (LSP, then tree-sitter, then text) opt-in

Decision (2026-10-09, creator, T334): `[plugins.graph] backend` keeps `tags` (tree-sitter tags index) as the default, so the default answers stay byte-identical (Gate P30, `graph_contract.rs`). `backend = "lsp"` already falls back to tags since T376: when the server is missing, not ready, dead or answers "nothing", the answer is the tags one headed `(tags; lsp: <reason>)` with an `lsp_fallback` `Measurement` row (`docs/lsp.md`, "Without the server"). T329 adds `backend = "auto"` as an opt-in value: LSP first, tree-sitter second, plain text search last. `"tags"` and `"text"` pin one mode with no fallback; `"lsp"` keeps the T376 fallback to tags. Changing the default later needs a new gate with recorded `lsp.*` cold and warm latency rows, the `lsp_fallback` rate, and a creator decision that supersedes P30.

Backends are chosen per project and per language, not once per process: in a scope where A is Rust with rust-analyzer installed and B is Go with no server, A's rows come from LSP and B's from tree-sitter, in the same answer.

**Mode 1: LSP (first under `auto` and `lsp`).**

- Used when a server for the project's language is configured and works: the marker file is found (`Cargo.toml`, `compile_commands.json`, `tsconfig.json`, `pubspec.yaml`, plus any added later) and the server binary is on `PATH` (or resolved through `rustup which rust-analyzer`, as today), spawns over stdio, and answers `initialize`.
- Gives the most precise answers: type-position references, trait/interface implementations, re-exports and macro-expanded calls that tags miss.
- Cross-project traversal uses each project's own server; a definition location the server returns inside a linked project's root is mapped to that project and labelled with it.
- A server that starts but crashes or times out mid-session (default per-request timeout 10 s) marks that project's LSP as failed, answers the current request from the next mode and says so in the result (see "Which mode answered" below). It is not respawned on every request (see 6b).
- Indexing on large projects: while the server is still indexing (`$/progress` not finished), requests wait up to the timeout; on timeout they fall back to tree-sitter for that request only and keep LSP as the working mode.

**Mode 2: tree-sitter (fallback).**

- Used when LSP is not configured or not working for that project, and a tree-sitter grammar for the project's languages is compiled into rtok (the existing tags index in SQLite).
- Answers from the existing per-project tags index: definitions, call sites by name, outlines. It indexes the project on first use if needed, with the usual stale banner for pending files.
- Known limits, stated in the result when they matter: name-based resolution (several definitions with the same name are reported as ambiguous), no type-position references, no macro expansion.
- Cross-project traversal joins the tags indexes of every project in the scope by symbol name, preferring a definition in the selected project, then in directly linked projects, then transitively linked ones.

**Mode 3: plain text search (last resort).**

- Used when neither LSP nor a tree-sitter grammar is available for the project (for example a language rtok has no grammar for).
- Searches in process with the crates rtok already uses for its `search` tool (`ignore` for the walk and the ignore rules, `regex` for the matching; T4.5), so no `rg`, `grep` or `ssh` is spawned (D6, D18), with word-boundary patterns built from the symbol name and simple per-language definition patterns (`fn name`, `def name`, `function name`, `class name`, `func name`). Roots are local paths; `ssh://` roots are not supported (ideas.md I-118).
- Answers are best effort: `symbol` returns matching definition lines, `callers` returns lines that mention the name outside its definition, `outline` returns definition-pattern matches in the file, `impact` is limited to one level, and `dead` is not offered (the page and the tool say "not available in text mode" instead of guessing).
- Every text-mode result says it came from text search and may include false positives (comments, strings, same-named symbols). Output is capped the same way as other modes.
- Respects `.gitignore` and the project's ignore settings; never searches outside the project roots in the scope.

**When no mode works.** If all three fail for a project (no server, no grammar, or the root cannot be read), that project is dropped from the answer with one clear line ("project B: no graph backend available: ...") and the other projects still answer. If it is the only project, the tool returns that error.

**Which mode answered.** Every result says which mode answered for each project (page: a small LSP / tree-sitter / text tag next to the project badge; JSON: `backend` per project; text output: one header line). `Measurement` rows keep `kind = "lsp.*"` for LSP and gain `tags.*` and `text.*` kinds, so `rtok stats` shows how often each mode is used.

**Config.** `[plugins.graph] backend = "auto" | "lsp" | "tags" | "text"` (default `tags`), `lsp_timeout_ms = 40000`, and per-language overrides (`[plugins.graph.backend_by_language] go = "tags"`), documented in `docs/config.md` and `docs/lsp.md` (whose "Without the server" section already describes the T376 fallback).

#### 6b. Capability cache: check once; requests never re-probe; the health check re-checks failed records

Decision (2026-10-09, creator, T337): requests never probe; only the §8d background health check re-probes, so a backend-down or unreachable alert can clear without a restart while the request path keeps zero probes.

- The first graph request for a project (and language) runs the capability check: find the LSP marker and server binary and try to start it; check for a tree-sitter grammar; text search runs in process and needs only a readable root. The result is a per-project record such as "LSP works", or "LSP: rust-analyzer not on PATH; tree-sitter works", or "LSP and tree-sitter unavailable; text works".
- Later requests use that record directly: they go straight to the working mode and do not re-probe the modes that failed. No `PATH` lookup, no server spawn attempt and no grammar check runs again on each request.
- The record lives in memory for the hot path and is mirrored into the store (a per-project row, the same pattern as the co-change state), so separate processes (the MCP server, `rtok web`, the CLI, `rtok doctor`) see the same record. A new process reads the mirrored record instead of probing again. A request does not re-probe a failed record; the §8d health check does (below).
- A working mode that later breaks (server crash, repeated timeouts) is downgraded in the record once, and the next mode becomes the chosen one for that project until the health check sees the failed mode work again (two consecutive good checks); it is not re-probed per request.
- Changing `[plugins.graph] backend` or the per-language overrides in config clears the record for the affected projects (the config watcher already reloads settings); config changes and the §8d health check are the only things that update it.
- Adding a new project (manually, by session or by reference) runs the check once for that project only; existing records are untouched.
- `rtok graph projects --json` and the page show each project's record from the store, with `checked_at` and `next_probe_at`, so the user can see why a mode was chosen and when it is checked next.
- Concurrent first requests for the same project share one check (single-flight); they do not spawn several servers.

#### 7. CLI and MCP

- `rtok graph projects` lists projects, `rtok graph projects add <path>`, `remove <id|path>`, `select <id|path>`, `link <id|path>`, `unlink <id|path>`; all support `--json`.
- Every graph command and every graph MCP tool takes an optional `project` (id or path). Without it, the project is the caller's current directory (agents keep today's behaviour) and the scope includes that project's links, so an agent working in A automatically sees into the projects A references.
- MCP results carry the same `project` field per row as the CLI's JSON.

#### 8. Web UI

- Lands on the React SPA graph page (T310.8): selector, indicator, links panel, project badges in every list and in the graph drawing (one colour per project, with a legend).
- New `/ws` messages: project list, selection changed, links changed, per-project index progress.
- If T310.8 has not landed when the backend is ready, ship the registry, links, references, traversal, CLI, MCP and `/ws` first, and the page with T310.8.

#### 8a. Visual graph: projects overview and drill-down into one project

The graph page draws two levels of graph, both interactive (pan, zoom, drag, click), rendered from data sent over `/ws`.

**Level 1: projects overview (the page's landing view).**

- Header counters: total known projects, projects in the current scope, linked pairs, and projects with problems (missing, failed, no backend).
- A node per project, labelled with its name, sized by indexed symbol count, coloured per project (the same colour used for project badges everywhere), with a small backend tag (LSP / tree-sitter / text) and a state marker (indexing, stale, failed, missing).
- An edge per link, drawn as an arrow from the linking project to the linked one. Manual and auto links look different (solid vs dashed); hovering an auto link shows its reason (for example "Cargo path dependency `../ketch-core`"). Edge thickness reflects the number of cross-project references actually found between the two projects; a link with zero references found is drawn thin and grey with a tooltip saying so.
- The selected project is highlighted and its scope (everything reachable through links) is emphasised; projects outside the scope are dimmed but still shown.
- Interactions: click a node to select it as the current project; double-click (or an "Open" button) to drill into it; right-click or a node menu to link, unlink, re-index or remove; a filter box hides projects by name; a toggle shows only the current scope.
- Edge cases: one project only shows a single node and a hint about linking; cycles are drawn normally (no infinite layout); more than about 50 projects switches to a clustered layout grouped by origin, with a list view fallback; missing projects are drawn hollow and cannot be opened.

**Level 2: inside one project (drill-down).**

- Opening a project shows the relationships inside it as a graph: files, modules, types and functions/methods as nodes; "contains", "calls", "implements" and "imports" as edges. A breadcrumb (`All projects / rtok / src/plugins/graph`) leads back up, and the browser back button works (the drill-down state is in the URL).
- It starts at file/module level (files grouped by directory, edges are aggregated call/import counts between files) so a large project stays readable. Clicking a file expands it into its functions, methods and types; clicking a function focuses on it and shows its callers and callees (depth 1 by default, adjustable up to the same limit `impact` uses).
- Calls that leave the project into a linked project end at a node for that project (in its colour); clicking that node opens the target symbol inside the linked project, so the user can follow a call chain across projects visually, matching what cross-project traversal (6) returns.
- A side panel shows the selected node's details: path and line, signature, callers and callees lists, and "open in editor" (the `vscode://` / `file://` link rtok already uses where available).
- Search: typing a symbol name finds it in the project (and the scope) and focuses it on the graph.
- What the graph shows depends on the backend answering for that project (6a): LSP and tree-sitter give full call and containment edges; text mode shows files and definition-pattern matches only, with a banner saying call edges are not available in text mode.
- Large graphs: nodes beyond a cap (default 500 visible) are collapsed into "+N more" groups that expand on click; layout runs in a web worker so the page never freezes; the page shows a spinner while the graph data streams in.
- Live updates: when `watch` is on and files change, the affected nodes and edges update in place over `/ws` without resetting the layout or the user's zoom.
- Edge cases: an unindexed project shows the "Index now" empty state instead of an empty canvas; a project still indexing shows what is indexed so far, marked partial; dead symbols (when available) can be highlighted with a toggle; a file with parse errors is shown with a warning marker and its known nodes.

**Rendering: 3D with Three.js.**

- Both levels are drawn as a 3D graph in WebGL with Three.js. The preferred stack is `3d-force-graph` / `react-force-graph-3d` (Three.js plus a d3-force-3d layout) or `@react-three/fiber` with `@react-three/drei` if more control is needed; pick one in the first PR and record the choice and bundle size in `toolchain.md`. Library versions are pinned like other SPA dependencies.
- Camera: orbit (rotate, pan, zoom) with mouse, trackpad and touch; double-click a node flies the camera to it; a "reset view" button and a "fit all" button; the camera position is kept when data updates live.
- Nodes are spheres (projects) or smaller shapes per kind inside a project (file: cube, type: octahedron, function/method: sphere), coloured per project, with text labels as sprites that face the camera and hide past a zoom distance so the scene stays readable. Edges are lines with arrowheads (or directional particles for calls) and the same solid/dashed and thickness rules as above.
- Layout runs as a 3D force simulation in a web worker; it settles and then stops (no constant CPU use when idle). Expanding a file or project adds nodes near their parent instead of re-laying out the whole scene.
- Selection, hover tooltips, the side panel, search-to-focus and the right-click menu work the same as described above, using Three.js raycasting for picking.
- A 2D toggle shows the same graph flat (same library in 2D mode, or a 2D canvas renderer) for users who prefer it; the choice is remembered.
- Performance targets: 60 fps orbiting with 500 visible nodes and 2,000 edges on a 2020 laptop's integrated GPU; above the visible cap nodes are grouped (as above). Instanced meshes are used for nodes when counts are high.
- Fallbacks and edge cases: no WebGL (blocked, old browser, headless without GPU) switches to the 2D renderer with a notice; a lost WebGL context is restored automatically or falls back to 2D; `prefers-reduced-motion` disables camera fly-to and particle animation; the keyboard list view stays available in 3D mode; the 3D scene is disposed (geometries, materials, renderer) when leaving the page so memory does not grow when switching tabs.
- Tests: Vitest for the data-to-scene mapping (nodes, edges, colours, grouping) without WebGL; Playwright with software WebGL (SwiftShader) checks the canvas renders, a node click selects it, and the no-WebGL path shows the 2D fallback; Storybook stories for both levels with fixture data.

**Accessibility and themes.** Both levels work in dark and light themes at 375 and 1280 px; every graph has a keyboard-navigable list view with the same data (nodes, edges, counts) for screen readers and small screens; colours are not the only signal (shapes and labels carry the same meaning).

#### 8b. Two-part graph UI: interactive explorer and read-only live graph

The graph page is split into two parts that show the same graph data side by side (stacked on narrow screens).

**Part 1: interactive explorer.** Everything described in 8a: the user clicks, selects, drills down, expands, searches, links and unlinks, and moves the camera. Nothing happening in the background moves this view; it changes only when the user acts (or when indexed data changes under `watch`).

**Part 2: live graph (read-only).** The same graph, rendered with the same layout, colours and shapes, but purely for watching:

- No interaction at all: no click, hover menus, selection, drag, expand, search or link actions; no tooltips that need hovering. Pointer and keyboard events on the canvas are ignored, and the cursor stays the default arrow so it never looks clickable.
- The camera is driven automatically: it frames whatever is being queried right now and eases back to an overview when activity stops. The user cannot move it.
- It follows the level shown in part 1 (projects overview, or the project the user drilled into) so both parts show the same part of the graph; the live graph does not drill down by itself. Queries on symbols outside what part 1 shows light up the nearest visible ancestor with a counter.
- It shows only what the graph is being asked right now and how the data changes: every `symbol`, `callers`, `impact`, `explore`, `outline`, `affected` and `dead` call from MCP, the CLI or part 1 itself.

**Layout controls (outside the canvases).** A splitter between the parts (drag to resize, double-click to reset to 50/50), buttons to maximise either part, and a "Hide live graph" toggle; the choice is remembered. On screens narrower than 900 px the parts stack, live graph below, collapsed to its metrics strip until expanded. These controls are the only things the user operates for part 2; the live canvas itself stays read-only.

**What the live graph shows on the canvas.**

- When a call starts, the queried symbol's node (and its project node on the overview) pulses in an "in progress" colour; when it ends, the nodes in the answer (callers, callees, impact chain, explore hits) flash and the traversed edges animate along the path the query took, including edges crossing into linked projects.
- Each running call gets a small floating label next to its node with its tool name and a live counter of symbols returned so far; the label fades a few seconds after the call ends.
- Nodes queried often build up a heat glow that decays over a window (default 5 minutes), so hot spots are visible at a glance.
- Several concurrent calls are shown at once, each in its own accent so their paths can be told apart; more than 8 concurrent calls are merged into one "busy" pulse with a count.

**Live metric displays (read-only, updating in real time).** Arranged as a strip above the live canvas and a feed beside it; every number updates as events arrive, with a short count-up animation (disabled under `prefers-reduced-motion`).

- **Now running:** count of calls in progress, and for each: tool, symbol or query, caller (agent id and host from T283/T284, or "web" / "cli"), project, backend answering (LSP / tree-sitter / text), elapsed time ticking up.
- **Symbols requested:** for the current call, the size of its target set (for example the symbols an `impact` at depth 3 expands to); for the window, the running total. Shown as a number with a sparkline of the last 60 s.
- **Symbols returned:** same layout; the per-call value counts up while the answer streams; the ratio returned/requested is shown as a small bar.
- **Tokens sent / tokens without rtok / saved:** for the last call and for the window: answer size, the size of the unreduced answer (what a plain read or grep of the same data would have returned), and the saving in tokens and percent, shown as a large number with a sparkline. Values come from the same `Measurement` rows `rtok stats` uses, so they match `rtok stats` exactly.
- **Latency:** last call, p50 and p95 for the window, as numbers with a sparkline.
- **Files touched and projects crossed:** per call and window totals.
- **Per-tool breakdown:** a live bar per tool (`callers`, `impact`, ...) with call counts and tokens saved in the window.
- **Backend use:** live shares of LSP / tree-sitter / text answers and the number of fallbacks in the window.
- **Cache and caps:** how many answers were cut by a cap and how many fell back, as live counters.
- **Call feed:** newest first, one row per finished call with tool, symbol, caller, project, backend, requested, returned, tokens saved and latency; failed calls in red with the error; interrupted calls marked as such. The feed scrolls by itself and keeps the last 200 rows; it is read-only like the canvas (no click to replay), but it can be filtered by agent, tool and project with controls above it.
- **Window selector:** totals cover the last 1, 5 or 15 minutes, or "since `rtok web` started"; changing it recomputes from the store, not from what the browser happened to receive.
- **Freeze button:** stops the live canvas and the displays updating so a moment can be read; events keep arriving in the background and the view catches up on unfreeze. Totals never drop events.

**Data path.**

- The graph plugin emits a start event and an end event per call (with the numbers above, and a progress event while a long answer streams) on the existing `/ws` stream. The page subscribes only while part 2 is visible, so a hidden or collapsed live graph costs nothing.
- Events from the MCP server process, CLI runs and `rtok web` all reach the page through the store (or the daemon channel the web UI already uses), so an agent's call in another process shows up within one second.
- Payloads carry ids, symbol names, paths and numbers, never source text.
- Rendering is batched per animation frame; a burst (for example 200 calls per second) is coalesced for display, while counters and totals still count every call.

**Edge cases.**

- No activity yet: the live graph shows the static graph dimmed and "Waiting for graph calls"; the displays show zeros, not blanks.
- A failing call (no backend, timeout) pulses red on its node and appears red in the feed with the error.
- A call still running when its process exits is marked "interrupted" after a timeout and stops counting as running.
- Calls on a project outside the current scope are counted in the totals and listed in the feed (marked "outside scope") but do not light up the canvas.
- `/ws` drops: the live part shows "reconnecting", then resumes; missed events are shown as a count and the totals are refreshed from the store.
- Part 1 drills into a project while calls are running: the live graph switches level with it and re-attaches running calls to the new view.
- Several browser tabs: each live graph receives the stream; closing or hiding them stops the subscription.
- WebGL unavailable: the live graph uses the same 2D fallback as part 1; the metric displays do not depend on WebGL.
- Live events stay local to `rtok web` (localhost by default); nothing leaves the machine.

**Config.** `[plugins.graph] live_heat_window_s = 300`, `live_max_events_per_s = 50` (rendering cap only), `live_feed_rows = 200`, documented in `docs/config.md`.

#### 8c. Export: graph as an image or JSON

- **What can be exported:** the projects overview, the current drill-down view, or a focused subgraph (a symbol with its callers/callees/impact at the chosen depth), from part 1. The live graph (part 2) can export a snapshot of its current frame as an image only.
- **Formats:**
  - PNG at 1x/2x/4x, transparent or theme background, with a legend (project colours, node shapes, edge styles) and a footer (project names, scope, backend per project, `indexed_at`, rtok version, export time).
  - SVG for the 2D rendering (vector, editable); in 3D mode SVG exports the current camera projection flattened to 2D.
  - JSON, versioned schema (`"schema": "rtok.graph.v1"`): `projects` (id, name, root as a path relative to the user's home or redacted, origin, backend, health), `links` (from, to, kind, reason, reference count), `nodes` (id, project, kind, name, path, line), `edges` (from, to, kind), and `meta` (scope, level, focus, depth, filters, export time, rtok version). The schema is documented in `docs/plugins.md` and checked by a JSON Schema file in the repo.
- **Where:** an "Export" menu on the page; `rtok graph export --format png|svg|json [--project ID] [--focus SYMBOL --depth N] [-o FILE]`; and an MCP tool `graph_export` (JSON only) so an agent can hand a graph to another agent or attach it to a PR.
- **Sharing safety:** absolute paths, the home directory and the user name are redacted by default (`--no-redact` to keep them); source text is never included; file names and symbol names are, and the export dialog says so.
- **Edge cases:** an export larger than the visible cap includes every node in JSON but only the visible ones in images, and the image footer says "N nodes hidden"; exporting while indexing marks the export partial in `meta` and the footer; text-mode projects export without call edges and say so; images render offscreen at the requested size, not a screenshot of the window, so the result does not depend on window size; no WebGL means PNG comes from the 2D renderer.
- **Import (read-only):** the page can open an exported JSON to view it (no live data, banner "viewing export from ..."), which is also how diffs against a saved export work (8e).

#### 8d. Alerts: linked project down or unreachable

- **What raises an alert:** a project in the current scope (including auto-linked references) becomes **missing** (root deleted or moved), **unreachable** (a network share stops answering, an external disk is unmounted), **backend down** (its working backend from 6b fails and no fallback works), **index failing** (re-index errors three times in a row), or **link broken** (a manifest reference now points to a path that does not exist).
- **Detection:** the `watch` loop and every graph query update project state; a light background check runs every 60 s (`[plugins.graph] health_check_interval_s`) in every process that hosts graph (the MCP server and `rtok web`), for projects in an open scope. It is the only code that re-probes (requests never do, 6b), and it reads the capability record (6b) from the store. The cheap tier runs every interval: the project root exists (missing, unmounted), the server binary has appeared on `PATH` (path and mtime), a running server is still alive (`try_wait`). A failed or degraded record is restarted (server spawn plus `initialize`) only when the cheap tier sees a change (new binary, root back) or on a backoff that starts at 60 s, doubles, and is capped (the cap is fixed in T329.17). A state must persist for two checks before it alerts, to avoid flapping on a brief unmount.
- **Where alerts show:** a red badge on the project node and link edges in both parts, a toast and an alerts list on the graph page, a line in `rtok doctor`, `rtok graph projects` output (`state` and `alert` fields in `--json`), and a short notice in graph MCP answers that touch an affected project ("project B unreachable since 14:02; results exclude B"). Agents therefore learn about it in the answer they are already reading.
- **Optional push:** if T288 (push unread messages to hooked agents) is available, an alert is delivered once to agents whose current scope includes the project; repeated failures do not repeat the message.
- **Recovery:** when the project comes back, the alert clears automatically after two consecutive good checks (the same two-check rule as raising), the capability record upgrades, a "recovered" entry is logged, and the project is re-indexed if files changed while it was away.
- **Edge cases:** a project removed on purpose from the registry never alerts; unlinking a broken project clears its alert for that scope; an alert on a project that is only transitively linked names the chain ("A to B to C: C missing"); many simultaneous alerts (for example a whole disk unmounted) collapse into one grouped alert.
- **Config:** `[plugins.graph] alerts = true`, `health_check_interval_s = 60`, documented in `docs/config.md`.

#### 8e. Diff: compare the graph before and after a change

- **What can be compared:** the current graph against (a) a git ref (`HEAD~1`, a branch, a commit, the merge base of a PR branch), (b) the working tree versus `HEAD` (uncommitted changes), or (c) a saved export (8c). Diffs work over the whole scope, so a change in B that affects A's call sites shows up in A.
- **How the "before" side is built:** for git refs, rtok indexes the files at that ref from the object database into a temporary index (no checkout, no change to the user's working tree), using the same backend chain (LSP is skipped for the old side when it would need a separate checkout; tree-sitter is used instead and the diff says so).
- **What the diff reports:** symbols added, removed, renamed (same body hash, different name or path), moved between files or projects, and changed (signature or body); call edges added and removed; links added and removed; and, for each changed symbol, its callers that are affected (the `impact` set), which is the part agents need for review.
- **Where:**
  - Page: a "Compare" mode in part 1 colours nodes and edges (added green, removed red, changed amber, moved blue) and lists changes in a side panel; the live graph is unaffected.
  - CLI: `rtok graph diff [--from REF|--from-export FILE] [--to REF|working] [--project ID] [--json]`.
  - MCP: `graph_diff` returning a capped summary (counts, top changed symbols with affected callers) and an id to page through details, so an agent reviewing a PR gets a short answer by default.
- **Edge cases:** a ref that does not exist returns a clear error; a diff spanning projects at different git states diffs each project against its own ref (a `--from` per project is allowed); generated or vendored files follow the project's ignore rules; very large diffs are capped like other answers with a "more" id; renames are detected only when unambiguous, otherwise shown as remove plus add; binary or unparsed files are listed as "changed, not analysed".

#### 8f. Health score per project

- **Score:** 0 to 100 per project, shown as a coloured ring on the project node (green 80+, amber 50 to 79, red below 50) with the breakdown on hover in part 1 and in the project list, and as `health` in `rtok graph projects --json` and MCP answers.
- **Components (weights in brackets, each 0 to 1):**
  - **Index freshness [40%]:** 1 when no files are pending and the last index is newer than the last file change; drops with the share of pending files and with age (0 when more than 20% of files are pending or the index is older than 24 hours with changes since).
  - **Backend alive [30%]:** 1 when the configured backend works (so the default `tags` working scores 1); under `auto` or `lsp`, 1 when LSP works, 0.6 when running on the tree-sitter fallback and 0.3 on the text fallback; 0 when no backend works. Reads the cached capability record (6b) plus recent query failures.
  - **Links not broken [30%]:** the share of the project's links whose target is present, reachable and indexed; a project with no links scores 1 here.
- **Explained, not just a number:** each score comes with the reasons that lowered it ("12 files pending", "rust-analyzer not on PATH, using tree-sitter", "link to ../foo broken"), and a suggested fix for each (re-index, install the server, which the health check picks up within one interval or on restart, fix or remove the link).
- **Scope score:** the selected project's scope shows its lowest project score (the weakest link decides), not an average.
- **Agents:** graph MCP answers include a one-line health note when the scope's score is below 80, so an agent knows when results may be incomplete; `rtok doctor` lists every project under 80 with its reasons.
- **Edge cases:** a project being indexed for the first time shows "indexing" instead of a score; a missing project scores 0 and shows "missing"; text-only languages are not penalised beyond the backend component; scores update live as state changes and are recomputed at most once per second per project.

#### 9. Docs

`docs/plugins.md` (graph section: projects, links, references, scope, backends), `docs/lsp.md` (fallback chain and capability cache) and `docs/config.md` (the new `[plugins.graph]` keys), with `docs/ru/` and `docs/uk/` updated in the same change.

#### 10. Delivery

As PRs, backend first; do not merge them. Each PR becomes a subtask (`Tn.m`, D16) when it is claimed.

Dependencies: T310.8 for the page; T285 and T289 for worktree-based adding; the existing graph index and LSP integration.

Check: fixture repos under `tests/fixtures`, no network:

- Repo A has a Cargo path dependency on B; B has one on C; D is unrelated.
- Indexing A registers B and C (origin `reference`), indexes them and creates auto links A to B and B to C; D is not added.
- Selecting A shows A in the header with "+ 2 linked"; `callers` of a function defined in C returns call sites in A and B, each labelled with its project; `impact` from that function walks up into A.
- `dead` over A's scope does not report B's function that only A calls; selecting B alone does.
- Unlinking B from A removes B and C from A's scope, survives a re-index (no re-link), and `callers` no longer crosses projects.
- A manual link A to D adds D to the scope; a cycle (D links A) does not loop or duplicate rows.
- Removing the path dependency from A's manifest and re-indexing removes the auto link but keeps B in the registry.
- A reference to a missing path shows a warning and adds nothing; a deleted project root shows as missing and drops out of the scope.
- `reference_depth = 1` stops at B; `max_auto_projects` limits are reported on the page and in logs.
- A new agent session in a new directory registers it when `auto_add_projects` is on and not when it is off; `auto_link_references = false` adds no reference projects.
- The selection survives an `rtok web` restart and syncs between two browser tabs.
- MCP `callers` without `project` from A's directory crosses into B and C; with `project` set to D it does not.
- Backends, with `backend = "auto"`: with rust-analyzer on `PATH`, A answers from LSP (result tagged LSP) and finds a type-position reference tags would miss; with it removed from `PATH` and the MCP server restarted, A answers from tree-sitter (tagged tree-sitter); a fixture project in a language with no grammar answers from text search (tagged text, `dead` reported as not available); a scope mixing all three labels each project with its own mode.
- `backend = "lsp"` with no server answers from tags with the T376 `(tags; lsp: <reason>)` header and an `lsp_fallback` row; the default `tags` answers stay byte-identical (`graph_contract.rs`).
- A server that crashes mid-session: the current request is answered from tree-sitter with a notice, and later requests go straight to tree-sitter until the health check sees the server work again.
- Capability cache: a test counts probes; 100 requests to the same project after the first run zero further `PATH` lookups or spawn attempts; installing the server is picked up by the health check within one interval (or by a restart), while requests still run zero probes; changing `backend` in config re-checks only the affected projects; two concurrent first requests run one check.
- Visual graph, level 1: with A, B, C, D the page shows 4 projects, 3 in A's scope and 3 linked pairs (A to B, B to C, A to D); the A-to-B edge is dashed with the Cargo reason on hover, A-to-D is solid; clicking B selects it; a missing project is drawn hollow and cannot be opened.
- Visual graph, level 2: opening A shows its files with aggregated edges; expanding a file shows its functions; focusing the function that calls into C shows the edge ending at a C node, and clicking it opens the target symbol inside C; the breadcrumb and browser back return to the overview; a text-mode project shows the "call edges not available" banner; editing a file with `watch` on updates the node without resetting zoom; a fixture with more than 500 nodes shows "+N more" groups and the page stays responsive.
- 3D: both levels render in Three.js (Playwright with SwiftShader sees a non-empty canvas and can select a node by click); disabling WebGL shows the 2D fallback with a notice; the 2D/3D toggle is remembered across reloads; orbiting the 500-node fixture stays smooth and the layout stops when settled; leaving the page releases the WebGL context.
- Two-part UI: an MCP `callers` call from a separate process lights up the target node in the live graph within one second, animates the path into a linked project and adds a feed row whose symbols requested/returned, tokens and saving equal the matching `Measurement` row and `rtok stats`; part 1's camera and selection do not move; clicking, dragging, hovering and keyboard input on the live canvas change nothing (Playwright asserts no selection or camera change); drilling into a project in part 1 switches the live graph to it; freeze then unfreeze catches up without losing totals; a burst of 500 calls in 5 s keeps both parts responsive and the totals exact; a failing call shows red with its error; dropping and restoring `/ws` shows "reconnecting" and refreshes totals from the store; with the live part hidden, no live events are serialised; on a 375 px screen the live part stacks below as a metrics strip.
- Export: PNG, SVG and JSON exports of A's scope open correctly; the JSON validates against the schema; absolute paths and the user name are redacted by default; a 2,000-node scope exports every node to JSON and the PNG footer notes hidden nodes; `rtok graph export` and MCP `graph_export` produce the same JSON; importing the JSON shows it read-only.
- Alerts: unmounting (or renaming) B's directory raises "B missing" after two checks on the page, in `rtok doctor`, in `rtok graph projects --json` and as a notice in an MCP `callers` answer from A; restoring it clears the alert and re-indexes; a backend-down alert clears after the server is restored, without a restart; a broken manifest path raises "link broken"; unmounting several projects at once shows one grouped alert; a removed project never alerts.
- Diff: changing a function signature in B and running `rtok graph diff --from HEAD` from A reports the change and lists A's affected call sites; the working tree is untouched by building the old side; a rename is reported as a rename; an unknown ref errors clearly; MCP `graph_diff` returns a capped summary with a paging id.
- Health: a fully indexed A with LSP and intact links scores 100; with 30% of files pending it drops below 80 with the reason shown; on tree-sitter fallback under `auto` the backend component reads 0.6, and the default `tags` scores 1; a broken link lowers the links component; the scope shows the lowest score; an MCP answer from a scope under 80 includes the health note.
- Playwright covers the selector, the indicator and its states, link/unlink, project badges, backend tags, both graph levels, export, alerts, compare mode, health rings, 3D and 2D modes, the two-part layout with the read-only live graph and its metric displays, and the list-view fallback; `just check`.


### T329.28. Graph page: live camera, 3D live view and the remaining live displays

Remainder of T329 §8b after T329.27 (which shipped the read-only 2D live canvas: `Scene2D`'s `live` prop, `live/lit.ts` for the lit state, `live/LiveGraph.tsx` and the splitter): the automatic camera that frames the running call and eases back to an overview (2D and 3D), the 3D live canvas (Three.js stage: read-only, heat glow, accents), maximise buttons and the collapsed metrics strip under 900 px, running labels with counters, count-up animation, sparklines, latency p50 and p95, symbols requested and returned (needs them added to the T329.15 events first), files touched and projects crossed, fallbacks and cap counters, the "outside scope" mark and the nearest-visible-ancestor counter, a caller column that names the agent and host instead of the session id (the events carry only the session), "since `rtok web` started" read from the store instead of since the page opened, `[plugins.graph] live_*` config keys with `docs/config.md`. A TUI counterpart is not planned yet (D27); ask the creator for a task. Depends on T329.27.

Check: the live canvas frames a running call and eases back to an overview in 2D and 3D; the latency and symbol counters match the events; the config keys are read and documented; Vitest, stories (axe) and Playwright; `just check`.

### T329.29. Graph page: Compare mode and the remaining diff reports

The rest of T329 §8e after T329.18 (core `rtok graph diff` and MCP `graph_diff`). Page: a "Compare" mode in part 1 colours nodes and edges (added green, removed red, changed amber, moved blue), lists the changes in a side panel and leaves the live graph unaffected; the page asks the same diff the CLI computes. Also: `--from-export FILE` (the reader is `export::read` from T329.16), project links added and removed, a `--from` per project, and the "changed, not analysed" listing for binary or unparsed files. Docs in en, ru, uk. Depends on T329.18, T329.22 and T329.16.

Check: a signature change shows amber on the page and in its side panel, a removed function red, an added one green; the live graph keeps running; a diff against a saved export works; Vitest, stories (axe) and Playwright; `just check`.

### T329.31. Graph export: SVG, PNG and the page's Export menu and import view

The rest of T329 §8c after T329.16 (split at claim time: the backend half fills the 500-line cap). T329.16 ships the `rtok.graph.v1` schema file, JSON export with redaction, `rtok graph export`, MCP `graph_export` and a read-only importer (`export::read`, `rtok graph export --from FILE`). Here: `--format json|svg|png` on `rtok graph export`; SVG drawn from the `Export` type of `src/plugins/graph/export.rs` (so a saved file draws the same picture), at most about 200 drawn nodes with the footer "N nodes hidden" while the JSON keeps every node, a legend (project colours, node shapes, edge styles) and a footer (project names, scope, backend per project, `indexed_at`, rtok version, export time, partial marker); PNG at 1x, 2x and 4x (transparent or theme background) rasterised from that SVG with a maintained crate (check `rust.md`: `resvg`) and `--scale N --transparent`; the "Export" menu on the Graph page (overview, current drill-down, focused subgraph; image of the live frame only for part 2; the dialog says that file and symbol names are included); and the page opening an exported JSON read-only with the banner "viewing export from ...". D27: anything the command prints is a page on web and tui, so the TUI gets the same export action. Docs in en, ru, uk. Depends on T329.16, T329.14.

Check: SVG, and PNG exports at 1x, 2x and 4x, of A's scope open and match; the page exports and imports the same JSON the CLI writes; Vitest, stories (axe) and Playwright; `just check`.

### T329.30. Graph page: health ring and breakdown per project

The page half of T329.19 (the backend half is in `done.md`). T329 §8f "Score": a coloured ring on the project node (green 80 and up, amber 50 to 79, red below 50; grey for `indexing`) with the breakdown (the three components, the reasons and their fixes) on hover and in the project list, and the scope's lowest score (`ProjectRow.scope_health`) beside the selected project. The data is `ProjectRow.health` and `ProjectRow.scope_health`, already in `/ws` and `web/src/api/snapshot.gen.ts`. Depends on T329.19.

Check: Vitest and a story (axe) for a project at 100, one at 60 with two reasons, one `indexing` and one `missing`; `just check`, `just spa-stories` and `just spa-e2e`.


### T356. Never index `$HOME` or `/` as a graph root

Found 2026-10-02 (T352 research): `symbols` holds 617,319 rows (~120 MB plus indexes) under the root `/Users/listepo` — `.config/amp/plugins`, `.cursor/extensions`, `.grok/bundled`, `go/pkg/mod`, `.motive/node_modules`. The graph root is the `rtok mcp` process cwd (`std::env::current_dir()` in `src/plugins/graph/mod.rs`), so a server launched in the home directory (no `roots/list` answer yet, or a host without roots) walks the whole home on its first `symbol`/`callers`/`explore` call, and the rows never leave: `delete_symbols_missing` runs only when that same root is re-indexed.

Done when: the graph refuses a root that is the home directory or the filesystem root (an error that says to pass a path or open a project, no walk), and existing `symbols`/`symbol_stale` rows of such roots are dropped once (store retention or a migration). Indexing of real projects is unchanged.

Check: graph tests for both refused roots and an accepted project root; a store test that drops the home-root rows; `symbols` size on a copy of the real store before and after, recorded in this card.

Execution plan (after T352 lands — it adds `drop_vanished_symbol_roots` to retention): reuse `plugins::read::walk_root_ok` (T263: refuses `/` and the home directory for the watcher) in the graph's root choice (`src/plugins/graph/mod.rs`, the `current_dir()` call sites) and return `no project: pass path or open a project (roots)`; extend T352's root drop so a root that fails `walk_root_ok` is dropped like a vanished one. Tests: graph refuses home and `/`, accepts a temp project; retention drops a home-root row.

### T369.1. Measure grep_symbol follow-up rate after an opt-in window

Turn `plugins.guard.grep_symbol` on for a dated window and measure the share of follow-up Grep/Read on the same name within 3 calls (`guard/grep_symbol` rows against the transcripts). At most 25 % means propose default-on to the creator; above it, keep the flag opt-in and record why. Also re-measure the PreToolUse hook p95 on an idle machine (`cargo test --release --test latency -- --test-threads=1`; the flag-on test is `latency_hook_grep_symbol_answer_p95_under_10ms`); on 2026-10-06 the load average was 46 and even the baseline run failed the 10 ms gate. Record the result as a dated row in `research.md` §29.5.

Check: a dated `research.md` §29.5 row with the follow-up share and the idle-machine p95 (under 10 ms); `just check`.

### T370. SessionStart repo map ranked by file-level personalized PageRank

From the Empryo study (idea-only, clean-room; Empryo `repo-map.ts` PageRank and render, `repo-map-utils.ts:479-493`: damping 0.85, 20 iterations, budget 1500 / 2500 / 4000 tokens). T52.3 / I-28 ranks definitions by raw reference count (`symbol_top_refs`, `src/store/symbols.rs:614`), off by default and without a live A/B; `repo_map` (`src/plugins/graph/mod.rs:1090`) pops and re-estimates the rendered text in a loop, quadratic in the number of rows. A file graph (edge = file A references a name defined in file B, weighted by T368's IDF) with PageRank puts hub files first, and a personalization vector moves the map toward what the session is about.

Plan: new `src/plugins/graph/rank.rs` — CSR adjacency from one `symbols` scan, power iteration (0.85, 20 iterations or L1 delta < 1e-6), dangling mass spread uniformly. Global ranks persisted per root in a `file_rank` table (new migration), recomputed at index end; personalized ranks are computed at SessionStart and never persisted (Empryo issue #228: a stored personalized rank leaked one session's focus into the next). Personalization: git-dirty files, the last checkpoint's paths on `compact` (`src/plugins/checkpoint.rs`), and the SessionStart `source` (`crates/rtok-plugin-sdk/src/lib.rs:274`). Render top files with their top definitions; fill the budget in one pass with a running token estimate instead of pop-and-re-estimate. Host trait method in `crates/rtok-plugin-sdk/src/host.rs` and its Runtime impl in `src/plugin.rs`. Config `plugins.graph.map_rank = "refs" | "pagerank"`, default `refs` until the check passes.

Done when: with `map_rank = "pagerank"`, the SessionStart map lists files by rank under `map_tokens`, personalized by dirty files, and a stored rank never contains session state.

Check: unit tests for PageRank on a 4-node graph (known stationary vector, sum = 1 ± 1e-9) and for personalization; offline backtest over the last 200 commits of this repo: recall of the commit's touched files in a `map_tokens = 1000` map, `pagerank` minus `refs` ≥ 15 pp; SessionStart hook p95 ≤ 30 ms on this repo (divan bench); `just check`.

Execution plan (Claude Code / sonnet-5.5; fits one task, no split):
1. `src/plugins/graph/rank.rs`: file graph from one grouped `symbols` scan (edge A to B when A references a name defined in B, weight = refs x ln(1 + files / files-referencing-name) / files-defining-name; T368's shared IDF table replaces it when it lands), power iteration (0.85, 20 iterations or L1 < 1e-6, dangling mass uniform) over CSR, JSON encode/decode of the graph, and a one-pass renderer with a running token estimate. No crate: the maintained graph crates have no personalized PageRank.
2. Migration `0030_file_rank` (`file_rank (root PRIMARY KEY, graph TEXT)`): paths, global ranks, indexed mtimes, top defs per file and the edges, written at index end; a project removal drops it. Three `Symbols` host methods with default bodies (`symbol_file_scan`, `file_rank_get`, `file_rank_put`), Diesel impls in `src/store/symbols.rs`, Runtime in `src/plugin.rs`.
3. SessionStart reads the one row and never spawns a process (D1, T428's 10 ms): personalization seeds are files whose indexed mtime is under 24 h old (only when that set is a working set, 32 files at most; a fresh clone seeds nothing) plus, on `compact`, the paths of the session's last checkpoint (`checkpoint::last_paths`). The personalized rank is computed in memory and never stored.
4. Config `plugins.graph.map_rank = "refs" | "pagerank"` (default `refs`), `default.toml`, `docs/config.md`, trycmd and config_coverage goldens; `repo_map` branches on it, `refs` output unchanged.
5. Tests: 4-node stationary vector, personalization, no session state in the stored row, codec round trip, budget fill, store round trip and purge, SessionStart through the hook. An ignored backtest (`cargo test --lib backtest -- --ignored --nocapture`) reproduces the numbers recorded in `research.md` section 34; a latency test measures the hook with a populated row.

Progress (2026-10-06, Claude Code / sonnet-5.5): steps 1 to 5 are in. Backtest over the last 200 commits, 1000-token map: `refs` 26.7 %, `pagerank` 44.1 %, so +17.4 pp (card asks 15 pp; both halves of the history clear it). Open: the hook latency check. The host ran at a load of 30 to 50, the unmapped SessionStart hook itself missed 10 ms there (p95 13.9 ms), and the pagerank map added about 2 ms at p50 (decode 1.4 ms of a 700 KB stored graph, 20 iterations 0.33 ms). Re-run `cargo test --release --test latency session_start` on a quiet machine; the default stays `refs` until it passes.

### T378. Trigram prefilter for `search` (only if I-95 shows p95 > 200 ms)

From the Empryo study (idea-only, clean-room; Empryo `trigram.ts`: per-file trigram sets, candidate files = intersection of the query's literal trigrams). `search` (`src/plugins/read/search.rs:92`) walks and scans every file. Gate: I-95 (parallel walk) measures `search` p95 on a large repo first; if it is ≤ 200 ms, close this card with the number.

Plan: per-root trigram posting lists (`file_id` bitmaps, `roaring` only with a `Cargo.toml` justification, else sorted `Vec<u32>`) built in the graph index pass for text files ≤ 1 MB; queries with a literal of ≥ 3 bytes scan only candidate files; regexes without a usable literal take the full scan.

Done when: a literal `search` on a 50k-file repo scans only candidate files and returns the same hits.

Check: equivalence test (prefiltered vs full scan) over a fixture; divan bench `search` p95 −50 % on the large repo; index size growth recorded in the card; `just check`.

### T385. Proxy lanes and the optimization plan from `docs/research/optimization.md`

From `research.md` §31 and `docs/research/optimization.md` §6 (2026-10-04): a 12-step plan, "proposal, nothing built", which says to promote items into `plan.md` before coding. `src/proxy/` has no lane code. This card is the spec; split it into sub-tasks (T385.1 …) when claiming, one PR each, in this order:

1. Lane classifier and a `calls.kind` tag (P1).
2. Per-lane policy: bulk and batch bodies stay byte-identical in `compress` mode.
3. Defaults bench for `compress` mode, context editing, skills and `live_blobs`, with a dated `research.md` row (`tools_rewrite` stays in T124).
4. Batch observe, with `parse_results` into `usage`.
5. Flex on bulk and internal lanes, with a 429 policy.
6. Per-lane cache-hit ledger and a replay byte-stability test.
7. Isolation: per-lane upstream and an in-flight cap.
8. P28 Phase 1 measurement.
9. P28 Phase 2 async compressor on the `internal` lane.
10. Routing for internal and bulk calls (I-101's quality gate applies).
11. Deferred tool schemas and thinking replay (I-85, I-86 evidence first).
12. `rtok batch` CLI and a lane breakdown in `rtok report`.

Also: a cross-session read-dedup measurement from `calls` (optimization.md §5, "measure first"). Related ideas: I-84, I-85, I-86, I-101, I-102, I-21. The optimization doc's own ids (T250–T259) collide with `done.md`; use T385.x.

Plan: split 2026-10-04 into T385.1–T385.13 below, one PR each, taken in id order (T385.3, T385.8 and T385.13 are measurements and may run in parallel with the build steps). Boundaries from `docs/batch-flex.md` hold throughout: never convert a live agent turn into a Batch job, never rewrite Batch JSONL, everything lives under `src/proxy/`.

Check: each sub-task carries its own Check; this card closes when every step is done or dropped with its number in `research.md`.

### T385.3. Defaults bench: `compress` mode, context editing, skills, `live_blobs`

optimization.md §5. `rtok bench` cost per passed task for each setting on and off, recorded with a date in `research.md` (`tools_rewrite` is T124). Settings whose row shows a net saving with the pass rate held become default-on in a follow-up; the rest stay off with their number. Branch `t128-proxy-compress-default` (PR #562, `df2a13ba`) is prior art. Needs the creator's API spend for live arms (see T394).

Check: one dated `research.md` row per setting.

### T385.9. P28 Phase 2: async compressor on the `internal` lane

optimization.md §3.3. An LLMLingua-class compressor (arXiv 2403.12968; ACON arXiv 2510.00615 as the agent-context variant) runs on the `internal` lane, asynchronously, cached per archive id; the agent lane never waits for it; the original stays expandable. Only if T385.8 clears Gate P28.

Check: Gate P28 — the must-keep fixture survives, the bench beats v0.1 lossless on cost per passed task; `just check`.

### T385.10. Routing for `internal` and `bulk` calls

optimization.md §4.2 (D9, I-101). Route `internal` and opted-in `bulk` calls to a cheaper model or provider per the policy table; the `agent` lane is not routed.

Check: dollars per lane before/after from `stats --price` on fixtures; `just check`.

### T385.11. Deferred tool schemas and thinking replay

optimization.md §5 (I-85, I-86). Per-wire handling for deferred tool schemas and replayed thinking blocks, only where the measurement in each idea shows a saving (I-86 was rejected at 0.03 % — re-measure before building).

Check: per-wire tests and a dated bench row; `just check`.

### T385.12.2. Flex tier on `calls` and the lane/tier breakdown in `stats` and `report`

Split from T385.12; T385.6 and T385.12.1 are done (the price rows and `usage_by_model_tier` are in place). Record the effective `service_tier` of a proxied request, cost Flex usage at the `<model>@flex` row, and add a per-lane, per-tier breakdown to `rtok stats` and `rtok report` on top of T385.6's lane table.

Check: a report fixture with Batch and Flex rows; `just check`.

### T394. Run the paid live benches and record them

From `research.md` §3 (coaching nudges A/B) and §2 (A/B bench T9.2, `graph` T68.9): every live arm is unrun ("No live token, cache, or USD rows … the gate is do not enable"; T68.9 "Live API clause remains open"). Gate P9 and P8b clause 4 stay open until they run. Needs the creator's session and API spend: an agent sandbox cannot run them (OAuth expired).

Done means: one dated row per suite in §2 (nudges off/on, T9.2 legacy vs rtok, T68.9 graph MCP vs native) with the command, `claude --version`, mean input/cache/output tokens, `stats --price` cost and pass rate. Nudges become default-on only if cost per passed task falls and the pass rate holds; otherwise the row says "stays off". Gate P9 and P8b clause 4 close or fail on these rows.

Check: the rows are in §2 and `docs/comparison.md`; the gates are marked.

### T395. One real session as one OpenTelemetry trace in SigNoz and Maple

From `research.md` §2 "OpenTelemetry export (Gate P16)": still open is one real Claude Code session (hooks, MCP and proxy) exported as a single trace with an `invoke_agent` root and `chat {model}` spans, checked in SigNoz and Maple. `roadmap.md` says these clauses are Gate P18, but P18 in `done.md` has no such clause, so the item is orphaned. Needs a SigNoz or Maple account.

Done means: the `docs/otel.md` recipe for each backend is run with the proxy in front of a live session; the trace shape and `rtok_tokens_total` are recorded in §2 with a date, or the clause is dropped explicitly with the reason.

Check: dated rows in §2 for both backends, or the dropped clause in `roadmap.md`.

### T396. Verify the usage readers against real files

From `research.md` §30 (T358.3, T358.4): the Kilo and Gemini CLI readers are "not run on real data"; Copilot CLI `inputTokens` vs its `tokenDetails` block disagree and are marked unverified; whether a resumed Copilot session writes a second shutdown with run-only or cumulative totals is unverified (the reader sums them); pi forked-session id copying is unverified. Grok's `signals.json` reader waits on the creator accepting observed keys as a source (§30.7) — ask when claiming.

Done means: each reader runs once on a real file from a machine with that host; the Copilot resume semantics are confirmed or the reader stops summing duplicate shutdowns; each `unverified` tag in §30 is resolved or kept with a date.

Check: a fixture per confirmed shape; §30 updated with dates; `just check`.

### T398. Probe editors on a `worktree.useRelativePaths` worktree

From `research.md` §18.2 (T157): every non-interactive reader opens a relative-link worktree, but VS Code, Zed, Cursor and lazygit are untested, so the setting stays opt-in. It is the setting that would have prevented the 18 GB orphaned `graph-perf` worktree.

Done means: one dated row per editor in §18.2. If all pass, the `worktrees` skill and `AGENTS.md` gain the setting (T157's Check); if any fails, the failure is recorded and the setting stays off.

Check: the §18.2 table has four dated rows.

### T404. Evaluate a local draft model that the cloud model only verifies

Promoted from I-111 (Ivan, 2026-10-04). From `research.md` §16.3 #10: a local model drafts output and the cloud model verifies it, cutting cloud output tokens, which dominate cost on Fable (§2).

Done means: a research pass first — which hosts and APIs allow a pre-filled assistant draft, which local models are fast enough on Apple Silicon, how verification is prompted — recorded in `research.md` with primary sources. Build only if the bench shows cost per passed task falls with the pass rate held; otherwise close with the finding.

Check: the dated `research.md` section; the go/no-go recorded in this card's done entry.

### T405. Task-board extras for the agent task tools

Promoted from I-112 (Ivan, 2026-10-04). From `research.md` §28.4 F8, F9, F11, F19, F20: a `task` field on agent messages (F8); conflict and parallel markers between tasks (F9); an optional GitHub Issues or Linear exporter (F11); `CLAUDE_CODE_TASK_LIST_ID=<project>-<task>` set for the session (F19); a task board page via `dashboard_page` (F20).

Depends on I-103 (the task tools) and the creator's §28.5 decisions (source of truth, plugin vs separate crate, handoff file on the task branch) — ask before claiming. Split into one sub-task per item when claiming.

Check: each sub-task carries its own Check.

### T413. More agent hosts: popular agents rtok does not install into yet

Creator request 2026-10-05: add installers for popular coding agents missing from `src/agents/` (22 hosts on `9d6557c7`). One sub-task per host, one PR each. The list below is a lead, not evidence: every fact a sub-task relies on (binary, config paths, MCP entry format, hook events and payloads, plugin or extension API) is first written into `research.md` with its primary source (vendor docs or the repository's own files, URL plus date checked).

Shared shape for every sub-task, per `architecture.md` "New host" and D21:
1. Research: a `research.md` row for the host with the sources above; if a field is not documented, it stays out of the installer (never guessed).
2. `src/agents/<host>/mod.rs` implementing `Agent` (variants, detection, files, installed modules, apply) and `README.md` with the module table and `## Docs`; registered in `HOSTS` and `host()`; `[setup.<host>]` paths in `config/default.toml`, `src/config/mod.rs`, `docs/config.md`.
3. Reuse first: a host that forks or mirrors an existing one (named per card) shares that host's code through a helper, never a copy. Host configs: check only rtok's entry, keep the rest byte-for-byte; backups and `--dry-run` from `rtok-agent-sdk`.
4. `docs/agents.md` row; `agents_install` matrix row (install twice / remove twice / list) against fake homes, never the real agent.

5. Per-host check: the targeted tests above, `agents_doc` and `host_docs` green, `just check` green; one manual `rtok agents install <host> --dry-run` on this machine if the host is installed here, otherwise say "not installed here" in `done.md`.

Check: every sub-task below is closed in `done.md` with its per-host check.

### T413.3. `rtok agents install droid` — Factory Droid

`rtok agents usage` already lists Droid as `unsupported` (`research.md` §30.4). This adds the installer: MCP, and hooks or plugins if Factory documents them.

Check: the T413 per-host check (step 5) for this host.

Plan: primary Factory docs for the MCP entry and any hook file; write only documented keys; `src/agents/droid/` plus the host matrix.

### T413.4. `rtok agents install kiro` — Kiro IDE and CLI

Two variants (IDE and CLI) if both are documented. Hooks and MCP; `research.md` already cites Kiro specs, which this task does not touch.

Check: the T413 per-host check (step 5) for this host.

Plan: vendor docs for IDE and CLI config paths; one variant per documented file; share a helper if the JSON matches an existing host.

### T413.5. `rtok agents install amp` — Amp

Amp (Sourcegraph). MCP plus its plugin system if documented (`~/.config/amp/plugins` exists on this machine, `plan.md` T352 note). A plugin follows the `agents::plugin::HostPlugin` shape.

Check: the T413 per-host check (step 5) for this host.

Plan: Sourcegraph docs for the MCP entry and the plugin directory; `HostPlugin` only if the load path is documented.

### T413.6. `rtok agents install goose` — Goose

Goose (Block), CLI and desktop. MCP-native, so the minimum is the MCP entry; hooks only if documented.

Check: the T413 per-host check (step 5) for this host.

Plan: Block's docs for the MCP config file; hooks only if a payload is documented. MCP-only host via the existing stdio or local helper, whichever the file matches.

### T413.7. `rtok agents install continue` — Continue

VS Code / JetBrains extension and the `cn` CLI. MCP and rules; variants per documented config location.

Check: the T413 per-host check (step 5) for this host.

Plan: Continue docs for each config location; one variant per documented file; no guessed rules path.

### T413.8. `rtok agents install augment` — Augment Code and Auggie CLI

IDE extension and Auggie CLI. MCP and hooks if documented.

Check: the T413 per-host check (step 5) for this host.

Plan: Augment and Auggie docs for MCP and hooks; skip a surface whose file is not documented.

### T413.9. `rtok agents install junie` — JetBrains Junie

Junie in JetBrains IDEs and its CLI if one is documented. MCP first.

Check: the T413 per-host check (step 5) for this host.

Plan: JetBrains docs for the MCP file; a CLI variant only if its config path is documented.

### T413.10. `rtok agents install amazonq` — Amazon Q Developer CLI

Verify first whether Amazon Q Developer CLI is still maintained or replaced by the Kiro CLI; if replaced, close this sub-task into T413.4 and say so in `done.md`.

Check: the T413 per-host check (step 5) for this host.

Plan: AWS docs first. If the CLI is the Kiro CLI, close into T413.4 with the citation. Otherwise an MCP installer for the documented file only.

### T413.11. `rtok agents install crush` — Crush

Crush (Charm), open-source terminal agent. MCP and any documented hooks.

Check: the T413 per-host check (step 5) for this host.

Plan: Charm's docs and the Crush repo for the MCP config; hooks only with a documented payload.

### T413.12. `rtok agents install warp` — Warp

Warp terminal's agent. MCP and rules if they live in a file setup can edit; a setting stored only in the app's database is out of scope.

Check: the T413 per-host check (step 5) for this host.

Plan: Warp docs for a file-based MCP or rules path. If the setting lives only in the app database, close with that finding and no installer writes.

### T413.13. `rtok agents install trae` — Trae

Trae IDE (ByteDance). MCP; check whether its config mirrors the VS Code layout `src/agents/vscode/` already handles.

Check: the T413 per-host check (step 5) for this host.

Plan: Trae docs for the MCP file. Reuse the VS Code helper only if the key and path match; otherwise a thin host of its own.

### T413.14. `rtok agents install openhands` — OpenHands

OpenHands CLI (the local variant only; the cloud service is out of scope). MCP and any documented hooks.

Check: the T413 per-host check (step 5) for this host.

Plan: OpenHands local CLI docs for the MCP file. Cloud config stays out. Hooks only with a documented payload.

### T413.15. `rtok agents install reasonix` — DeepSeek Reasonix

DeepSeek-Reasonix (https://github.com/esengine/DeepSeek-Reasonix), CLI plus editor extension. Its docs list MCP, skills, memory and hooks in `~/.reasonix/config.json` (lead, checked 2026-10-05: https://esengine.github.io/DeepSeek-Reasonix/configuration.html); confirm the keys before writing them.

Check: the T413 per-host check (step 5) for this host.

Plan: re-read configuration.html and write only the keys it names. Skills and memory stay out unless that page says setup may edit them.

### T414. Web dashboard restyle on the brand pack, built from `brand/` sources

Creator request 2026-10-05: a new look for the `rtok web` SPA from the brand pack in `brand/` (tokens, logos, icons, `DESIGN.md`, on top of the `@pyrlyn/brand` base it pins). Creator decisions 2026-10-05: a restyle by the brandbook (pages and navigation stay; shell, panels, tables, KPIs and charts change); a mockup first, approved before the pages; our own `web/src/ui` React components on the brand roles (`--pyr-*`), not the base `.pyr-*` classes. Creator decisions 2026-10-05, later: UI/UX additions (grouped sidebar, command palette and shortcuts, clickable KPIs, live status with pause) and features (Δtok savings trend, table filters in the URL, CSV/JSON export), one sub-task each (T414.8–T414.14). Creator decisions 2026-10-05, later still: charts draw on canvas through ECharts 6, behind our own chart abstraction so the library can be swapped; hover shows a tooltip, and other places change live with the hover where that does not repeat the tooltip; small marks (budget grid, plugin bitset, token mix, share bars) stay DOM and share the same tooltip (T414.15, T414.16). Creator decision 2026-10-05, after the charts: open-source UI toolkits are allowed; behaviour (focus, keyboard, overlays, menus, dialogs, listboxes) comes from React Aria Components, styled by us on the `--pyr-*` roles, so the look stays ours (from T414.9 on; its `Autocomplete` serves the palette, so no `cmdk`).

Source rule: `web/` holds no copy of a brand file. Tokens, fonts, icons, logos and illustrations are imported from `brand/` and `brand/node_modules/@pyrlyn/brand` at build time; anything derived (CSS, raster sizes) is produced by a program in the build, never committed by hand. `brand/README.md` "Known gaps" and `PROVENANCE.md` "Adopting in each surface" list today's copies.

Check: every sub-task below is closed in `done.md`, and no file under `web/` is byte-identical to a file under `brand/` or `brand/node_modules/@pyrlyn/brand/base/`.

### T414.7. Re-shoot `web/screenshots/`; close the web admin gap in `brand/`

Regenerate `web/screenshots/` with the existing script; in `brand/README.md` "Known gaps" and `PROVENANCE.md` "Adopting in each surface" mark the web admin as adopted.

Check: `just check` green; `brand/README.md` no longer says the web admin ships its own copies.


### T416. Shared `change-preview` crate for dry-run output

Every command that changes the disk should preview it the same way, and ketch and cox carry the same need (ketch has its own dry-run paths; cox depends on `similar` and `diffy`). The renderer moves out of `src/render.rs` into a crate with a neutral name in `packages/crates` (`listepo/crates-packages`, tracked there as T1), released to crates.io by that repo's release-plz pipeline; rtok then depends on the crates.io version, because a path outside this repository does not resolve in CI. Blocks T416.1-T416.4.

The crate renders two kinds of change. A file edit (path, before, after) is a unified diff with the `a/` and `b/` headers `render::unified_diff` prints today, or in stat mode a `path | 7 +++--` line; a removal (path, bytes, files) is `- path  12.1 GB  48 213 files`. Both modes end with a totals line: `3 files changed, 12 insertions(+), 4 deletions(-)` for edits, `15 paths, 1 204 311 files, -106.2 GB` for removals. Colour goes through owo-colors' `if_supports_color` exactly as `render::paint` does; byte sizes through the crate's `human_bytes` (the same binary units as `info::human_bytes`); everything derives `Serialize` for `--json`.

Plan: in `packages/crates` (branch `t1-change-preview`), add the crate's project files (`AGENTS.md`, `plan.md`, `todo.md`, `done.md`, `roadmap.md`, `ideas.md`, `toolchain.md`) and the crate as a workspace member, with unit tests for both modes, the totals and the colour switch; `bump.yml` gains a `package` choice and `release.yml` reads the crate from the tag, so the pipeline that releases `file-backup` releases this crate too; one PR there; then `bump.yml -f package=change-preview` publishes it. The crates.io token must allow publishing the new crate name.

Check: `cargo test` and `cargo clippy -- -D warnings` green in `packages/crates`; the crate is on crates.io.

### T416.1. rtok renders previews through `change-preview`

Replace `render::unified_diff`, `render::file_diff`, `render::paint` and `info::human_bytes` with the crate (call sites: `config/mod.rs`, `config/validate.rs`, `plugins/memory/sync.rs`, `plugins/read/cache.rs`, `doctor/fix.rs`, `agents/mod.rs`, `agents/junk.rs`, `worktree/list.rs`, `worktree/clean.rs`). No output changes: the trycmd and installer tests stay byte-identical. Drop `similar` from `Cargo.toml` if nothing else uses it.

Check: `just check` green with no expected-output file touched.

### T416.2. Deletion commands: `--dry-run`, `--stat`, sizes and file counts

`worktree clean`, `worktree gc` and `agents junk clear` keep dry run as the default and gain `--dry-run` (the same thing, said explicitly; conflicts with `--yes`) and `--stat`. Both modes print one `- path  size  N files` line per path plus the totals line, because a removal's line already is its stat; `--stat` is accepted so every preview command takes the same flags. `junk::disk_usage_until` counts files next to bytes; `--json` gains `files`.

Check: trycmd cases for each command in default, `--stat`, `--dry-run --yes` (rejected) and `--json`; `just check` green.

### T416.3. `--stat` on the commands that show a diff

`config init`, `config set`, `memory sync`, `agents install`, `agents uninstall`, `agents update` and `doctor --fix` gain `--stat`: the per-file stat lines and the totals line instead of the diff. The default `--dry-run` diff ends with the same totals line.

Check: trycmd cases for `--dry-run` and `--dry-run --stat` on `config set` and one installer; `surface_parity` and `config_coverage` green; `just check` green.

### T416.4. `--dry-run` for the destructive commands without a preview

`worktree remove` (the worktree path, its branch and its size and file count), `completions install` and `completions uninstall` (the file diff), `graph projects remove` and `graph projects unlink` (the rows that would go). Out of scope, because they are easy to undo or change no files: `memory pin/unpin/retire/revise`, `agents send/status`, `graph projects add/select/link`, `demon *`, `otel flush`.

Check: a test per command that the dry run changes nothing and prints the preview; `just check` green.

### T428. SessionStart hook back under the 10 ms budget

Renumbered from T418 on 2026-10-06: T418 is the finished `rtok worktree gc` task in `done.md`.

`rtok hook session-start` on the rtok repo took 12.7, 34.2, 13.4 and 65.0 ms on four manual runs (rtok 0.15.1, 2026-10-05) and warned `hook SessionStart slow … over max_ms 10 ms`. The output stayed correct and the exit code 0, but the fail-open rule asks for ≤ 10 ms.

Done: per-plugin timing of SessionStart on a real store shows where the time goes (memory recall, checkpoint restore, agent id, or store open); the slow part is fixed or moved off the hook path; `tests/latency.rs` covers SessionStart with a populated notes store and stays under its p95 bound.

Check: `tests/latency.rs` SessionStart case green; five manual `rtok hook session-start` runs on the rtok repo print no `slow` warning; `just check`.

Execution plan:

1. Time each phase of the hook (store open, `register_agent`, `record_call`, project upsert, each plugin's `session_start`, `inject::apply`, tail) on a copy of the real store (485 MB, 525 notes), idle and with six hooks racing; temporary trace, not committed.
2. Fix what the numbers show: SessionStart does about eleven write commits (against six for PreToolUse) and `memory::recall` reads every note body twice. Queue the three SessionStart measurements and write them in one transaction (`Runtime::defer_measurements` / `flush_measurements`, `Store::insert_measurements_once`); read each body once in `memory/mod.rs`.
3. `tests/latency.rs`: a SessionStart case with 40 notes of ~4k tokens and a `session:*` note.
4. Verify: A/B of the traced binaries, the latency test in release, five manual runs, `just check`.

Status: steps 1-3 done. On the rtok repo SessionStart dispatch is 2-4 ms on an idle host; the `slow` warnings come from write-lock waits and host load, so the fix cuts commits and reads. Left: re-run the `tests/latency.rs` release gate on a quiet host (it fails for every event at load average 35-60 because the spawn floor is already about 9-10 ms) and the five manual runs.

### T436.3. Shared operation-icon crate for rtok and ketch

Split from T436 (2026-10-08), item 5: `OPERATION_ICONS`, `icon()`, `ICON_WIDTH` and the gutter padding are the same code in `apps/ketch/src/ui.rs` and rtok's `src/ui/style.rs` (only ketch's `Tone` vs rtok's `Kind` differs). Extract them into a crate with a neutral name in `packages/crates` (released by that repository's release-plz pipeline, as `change-preview` is in T416), then use it from both. No output change in either tool.

Check: the crate's unit tests (icon per verb, fallback, width); rtok's `src/ui/style.rs` and `tests/ui_style.rs` green on the crate; ketch's own tests green; `just check`.

### T436.4. Spinner on `agents install/update` through T276's `ProgressRunner`

Split from T436.2 (2026-10-08): T436.2 shipped the remaining waits and the operation icons on the `agents install/update/remove` header, but the card's install/update spinner rests on T276's `ProgressRunner`, which does not exist yet; the existing `with_loader("updating host")` stays until then. Depends on T276.

Check: `agents install` and `agents update` show one spinner per host on a TTY and nothing on a pipe (non-TTY test); trycmd snapshots unchanged; the creator's manual run of `rtok agents install` in a terminal.

### T476. TUI: select, link and unlink projects on the graph page

The web graph page already selects, links and unlinks projects (`ClientMessage::Project`, `project_write` in `src/web/mod.rs`, which calls `plugins::graph::projects::run`), and the TUI graph page cannot. D27 (amended 2026-10-10, T346) requires every write action on one UI surface to have its counterpart on the other. Done means: the TUI graph page selects a project and links or unlinks a pair with keys listed in `KEYS` (`src/tui/app.rs`), calling the same `plugins::graph::projects::run` actions as the web and the `rtok graph projects` commands; it shows the plan first and writes only after a confirm key; the outcome shows on the status line like the plugin toggle's (T15.4).

Check: a TUI test with a `TestBackend` drives the keys on a fixture registry and the registry equals what the CLI commands write; declining the confirm writes nothing; the key hints render from `KEYS`; `tests/surface_parity.rs` lists project select/link/unlink on both surfaces; `just check`.

### T477. TUI: re-index and remove projects (counterpart of the web actions)

T329 plans web actions to re-index ("Index now") and remove a project on the graph page; D27 (amended 2026-10-10, T346) requires the same actions in the TUI. Depends on the T329 subtask that adds those web actions and on T476 (the TUI project keys). Done means: the TUI graph page has re-index and remove keys that call the same functions as the web actions and the CLI commands, with the same guards: remove shows the plan (what leaves the registry, that no file is deleted) and needs a confirm key; re-index shows progress on the status line and leaves the old data usable while it runs.

Check: a TUI test on a fixture project: re-index brings a stale project to indexed and equals the CLI result, remove drops it from the registry only after the confirm, declining changes nothing; `tests/surface_parity.rs` lists both actions on both surfaces; `just check`.

### T479. TUI: clear safe junk with plan and confirm

T330.7 shipped the web button (`ClientMessage::Junk { junk: JunkRequest { action: plan|apply, paths } }`, frames `ServerFrame::JunkPlan` / `JunkCleared`, both carrying `junk_clear::Cleared`; `agents::junk_web::{plan, apply, JunkCard}` over `junk_clear::run_in`, with the filter naming every agent's `safe` kinds and `apply` taking only the paths the plan showed; `Snapshot.junk` is the card data); D27 (amended 2026-10-10, T346) requires the same action in the TUI, so reuse `junk_web::plan` / `apply`, do not add a second path. Done means: the TUI hosts page has a clear-safe-junk key that calls the same function as `rtok agents junk clear`, shows the dry-run plan (per agent and kind, sizes, space freed) and deletes only after a confirm key (`clear --yes` semantics, re-check before each delete), then shows "Freed X of Y planned".

Check: a TUI test on the T330 fixture home: the plan equals `clear`'s dry run, confirm removes exactly the safe items and no others, declining changes no file (tree hash before equals after); `tests/surface_parity.rs` lists the action on both surfaces; `just check`.

### T481. TUI: graph health score per project

T329.19 puts `ProjectRow.health` and `ProjectRow.scope_health` into `rtok graph projects`, `--json` and `/ws`; D27 (amended 2026-10-10, T346) requires the TUI to show them too, and the TUI `Model.projects` already carries both fields. Depends on T329.19. Done means: the TUI projects view shows each project's score (or `indexing` / `missing`) with the same colour bands as the page (green 80 and up, amber 50 to 79, red below 50), the selected project's breakdown (the three components, the reasons and their fixes) and the scope's lowest score.

Check: a TUI render test on fixture rows at 100, 60 with two reasons, `indexing` and `missing`; `tests/surface_parity.rs` lists the score on both surfaces; `just check`.

### T480. TUI: live graph calls panel

T329.26 put a live calls panel on the web graph page: the `{"type":"calls"}` stream of T329.15, the metric displays (now running, tokens, failures, window chips for 1, 5 and 15 minutes, per-tool bars, backend shares), freeze and unfreeze, and a 200-row call feed with filters. D27 (amended 2026-10-10, T346) requires the same view in `rtok tui`. Done means: the TUI graph page has a live calls pane that reads the same `graph_events` rows through the same poller as `src/web/live.rs` (no second reader), shows the same totals as the web panel and `rtok stats`, freezes and unfreezes without losing counts, and lists the feed with the same filters. The live canvas of T329.27 and T329.28 gets its own TUI task when those land. Depends on T329.26.

Check: a TUI test with a `TestBackend` feeds a fixture event batch and the pane's totals equal the web store's for the same batch; freeze holds the picture and unfreeze shows every held call; `tests/surface_parity.rs` lists the live calls view on both surfaces; `just check`.

## Reference

Historical phase notes (P0–P39) live in `done.md`. Companion evidence: `research.md`, `architecture.md`. Per-plugin plan: `roadmap.md`. Unapproved propositions: `ideas.md`.

Claim a `todo` row before work: set Status to `in progress` and Agent to `Provider / model`. Before stopping unfinished work, set Status to `todo` and clear Agent. When the Check passes, move the task entirely to `done.md` (Do/Check + Check result) and drop it from this table, its card, and `todo.md`.

### Decisions (read before any task)

| # | Decision | Why (evidence in `research.md`) |
| --- | --- | --- |
| D1 | **Rust, one static binary, in-tree plugins behind one trait + Cargo features.** No WASM, no subprocess plugins, no daemon **on the hook path in v0.1** (`rtok demon` supervises the long-running surfaces only, D22). A later WASM plugin host is P32 (landed); this repo still does not wrap third-party tools (D6). | Hooks run on every tool call; Rust cold start vs Python hooks. |
| D2 | **One binary, three surfaces:** `rtok hook` (Claude Code hooks), `rtok mcp` (MCP server), `rtok proxy` (`ANTHROPIC_BASE_URL`). | PostToolUse hooks cannot modify tool results. Only PreToolUse rewrite, MCP tool replacement, or a proxy can shrink what the model sees. |
| D3 | **Measurement first.** Nothing ships until `rtok stats` reads real usage from session logs and the proxy. Every plugin logs before/after. Metric = *context-token-turns* (tokens × turns they stay in context), plus output tokens. | Vendor claims vs measured savings; nobody in the stack measures end to end. |
| D4 | **Lossless by default.** Every compression keeps the original retrievable via `rtok expand <id>` / MCP `expand`. Lossy only where the source is regenerable (re-run the command). | Trust is the product. |
| D5 | **Injection budget.** All SessionStart/UserPromptSubmit injections go through one plugin with a per-turn token cap (default 800) and a byte-stable prefix. | Injections are re-read (cached) every turn. |
| D6 | **Every plugin is native, written from scratch in this repo. No third-party plugins.** A plugin never spawns, links, imports, or reads the data of another tool. Third parties extend rtok from outside through the public plugin API (`rtok-plugin-sdk`, `Registry::from_plugins`, `docs/plugin-authoring.md`), never through this repo. | One code path per method is what `Measurement` can attribute. |
| D7 | **Prompt "modes" (terse, YAGNI) are data files, not code.** | Measured effect must be A/B tested, not assumed. |
| D8 | **One SQLite file** (`~/.rtok/rtok.db`, WAL). Schema is D13. Raw archived payloads on disk under `~/.rtok/archive/` (D4); the DB holds indexes and inline JSON under a size cap. | Field tools converge on SQLite (+FTS5). |
| D9 | **Agents are provider-agnostic.** Route by job: low-cost for mechanical work; mid-tier for coding; high-performance for research only after the user confirms. Host names are products, not the implementer. | User constraint: cost-aware routing, any provider. |
| D10 | **Retire, don't stack.** Phase 9 replaces the legacy hook pile with ≤ 8 and drops every tool the A/B bench cannot justify. | Duplicated responsibilities across bash/read/memory/graph tools. |
| D11 | **The proxy speaks both wire formats.** Anthropic Messages and OpenAI Chat Completions / Responses are `Wire` adapters behind one proxy. Hosts point `ANTHROPIC_BASE_URL` or `OPENAI_BASE_URL` at rtok. | One proxy, two parsers is cheaper than two proxies. |
| D12 | **One config file holds every setting; every CLI flag is a config key.** Precedence: defaults < user file < `<git root>/.rtok.toml` < `RTOK_<SECTION>_<KEY>` env < flags. `rtok config show --sources` tells where each value came from. | Hooks, long-running servers, and benches need one precedence rule. |
| D13 | **Core persists through a sync ORM on bundled SQLite.** Diesel (`sqlite` + bundled `libsqlite3-sys` with FTS5). Plugins never write SQL; `Store` is the only DB owner. Hook path: metadata always, body only if under the inline cap — never archive, never fail the hook (D1). | Diesel is sync, so the ≤ 10 ms hook path stays blocking and fail-open. |
| D14 | **CLI is clap 4 (derive); config layers are figment; TOML writes are toml_edit.** Env is `RTOK_<SECTION>_<KEY>` looked up in a leaf table from `Config::default()`. | Clap owns the subcommand tree; Figment tracks per-key provenance. |
| D15 | **Every plugin is designed against alternatives before it is built.** Each catalogue plugin has `src/plugins/<id>/PLAN.md`. | From-scratch only pays if the design beats what it retires. |
| D16 | **One task = one PR.** Each task gets its own branch (or worktree) off `origin/main` and lands through its own pull request; never commit to `main` directly. The PR carries the `<task-id>: <title>` commit and the `plan.md` → `done.md` move. Delete the branch after merge. A task that needs several PRs is split into subtasks `Tn.m`, one PR each; the parent card stays as the epic and moves to `done.md` with its last subtask. | Every change passes CI before it reaches `main`; concurrent agents stop colliding in one checkout. |
| D18 | **The graph index lives in SQLite with the ledgers (D8).** LadybugDB and Grafeo were gated, frozen, then removed (P39). No live `lbug` / `graph-lbug` / `symbols_lbug.rs` / `grafeo` feature flags. SQL for symbols lives only in `src/store/symbols.rs`. D6 holds: no spawned graph tool. | Both graph-store candidates were priced and deleted per the gate. Survey archive: `src/plugins/graph/PLAN.md`. |
| D19 | **Observability is a projection of the ledgers, never a second recorder.** OpenTelemetry export reads existing rows and posts OTLP/HTTP JSON. Nothing runs on the hook path. | Delivery is at-least-once behind a per-stream watermark. |
| D20 | **Local web UI is an operator surface, not a catalogue plugin.** `rtok web` serves axum + the embedded React SPA (`web/`, T310). The SPA is a static bundle, not linked code, and `rtok hook` never loads it. | Pulling a UI stack into `rtok hook` would fail the size/latency gate. |
| D21 | **Every new plugin is plugin and MCP as one unit, a singleton, with one call path per capability.** Host plugins load in that host's desktop app and its CLI. Missing `rtok`: fail open and print that it must be installed with ketch. | Duplicate MCP processes and duplicate call paths break D18 and measurement. |
| D22 | **`rtok demon` supervises rtok's own long-running surfaces**, not a catalogue plugin. Allow-list names (`proxy`, `mcp`, `dashboard`). Nothing in it is on the hook path. | The proxy is the wire hop; when it dies every host silently loses it. |
| D23 | **`rtok tui` and `rtok web` are two renderings of one operator model.** A page that exists on one surface and not the other is a defect. | Two independently built surfaces drift. |
| D24 | **`rtok report` renders; it never computes a number of its own.** It reads the D23 operator model. Recommendations are rules over those rows, never an LLM call. | A saving that is not a `Measurement` row does not exist (D3). |
| D25 | **The plugin contract is `rtok-plugin-sdk`.** Required methods are explicit; event methods keep no-op defaults. `rtok` is the only dispatcher. | One contract, no in-tree shortcut. |
| D26 | **One log with two readers:** a rotating text file and the `logs` table OTel exports. Bounded (`max_bytes` 1 MiB, `files` 5). | An unbounded log on a long-running proxy is a disk-full bug. |
| D27 | **Anything a command prints, or the store keeps, is a page on `rtok web` and `rtok tui`.** Writing actions may appear on both, each calling the same function as its CLI command with the same guards (dry-run plan, then confirm); every write action on one UI surface needs its counterpart on the other (D23 applies to writes too; creator decision 2026-10-10, T346). | The two surfaces plus CLI must not disagree about what a session is, or about what a write does. |
| D28 | **The agent-host contract is `rtok-agent-sdk`.** Installers go through it; host-specific code stays in `src/setup/<host>.rs`. | One write cycle, one plugin-offer body. |
| D29 | **Unit tests prefer a virtual filesystem (`testutil::Vfs`) over host TempDir/std::fs.** Pure path/content/size logic must not require real disk; Windows/macOS quirks are simulated in Vfs. Migrate hottest suites first (read/search/cmd/setup) as T56.x — not a big-bang rewrite of e2e. | Hermetic tests; reproducible CI; path-case and spaced-path bugs (T55) need a simulated FS. |
| D30 | **HTTPS uses webpki Mozilla roots (`use_preconfigured_tls`); one binary.** Corporate CAs via `SSL_CERT_FILE` (curl parity, fail closed). reqwest 0.13 `rustls` still links `rustls-platform-verifier`; `otool` showed Security.framework still present (T53.3). A second hook binary was rejected. | I-32: 1.3–1.5 ms dyld; dropping the `rustls` feature does not compile. |
| D31 | **Claude Code's `WorktreeCreate`/`WorktreeRemove` hooks route through `rtok worktree` and are exempt from the 10 ms rule (T159).** The 10 ms / unmodified-input budget (D1) binds the per-tool-call hot path. These two events fire once per worktree, replace the host's own create/remove, and must spawn git (a fetch and `git worktree add`). Fail open means the host never loses the ability to work: on any rtok error `WorktreeCreate` still prints a path, made by plain git at the host's own default (`<repo>/.claude/worktrees/<name>`, branch `worktree-<name>`); `WorktreeRemove` deletes nothing on error, never forces, and exits non-zero so the host keeps the directory and shows the reason (the host counts exit 0 as removed). Plugin only: `scripts/worktree.sh` carries the fallback, so `settings.json` never gets these entries. | The host's contract (`research.md` §18.3, re-checked 2026-10-02): a create hook that prints no path fails the session, so "print nothing, exit 0" is not available here. |
| D32 | **An optional resident hook process (T178).** `rtok hook --serve` answers `rtok-hook`, a std-only client, over a Unix socket (Windows: a named pipe); `rtok demon` supervises it as the service `hook`, or the hook starts it detached, rate-limited by a lock file. This supersedes D1's "no daemon on the hook path" and D22's "nothing in it is on the hook path" for the `hook` service only. Without it everything works as today: the client runs `rtok hook` when the resident is absent or refuses (another version or config environment), and prints `{}` when it does not answer within 50 ms. | Process start is ~11 ms of the ~14 ms Claude Code waits per hook (research.md §19); a fresh process cannot meet the 10 ms budget. |
| D33 | **rtok's MCP lives in each agent's own config, not in its plugins (T275, amends D21 for MCP).** Install and update always write the config entry `rtok`; only `remove` takes it out, and a plugin no longer suppresses or strips it. Where an agent would show a plugin server next to the config entry (Claude Code and Desktop, Cursor, Copilot, Codex, VS Code, ZCode, Kimi, Grok; `research.md` §25), the rtok plugin ships no MCP server and keeps its hooks, skills and agents. Gemini keeps both, since settings.json wins over an extension's same-name server. Same-name entries across one agent's files are left to the agent to merge. Hooks keep D21 unchanged. `rtok doctor --fix` never removes the `rtok` config entry (T332). |
| D34 | **rtok gives every agent session its own id and owns its worktrees the same way on every host (T281–T290, creator request 2026-09-27).** The agent id is a random UUIDv4 issued by rtok per host session (sub-agents get their own, with a parent), shown as its first 8 hex chars; any unique prefix of 4+ chars is accepted. Not UUIDv7: its leading hex is a timestamp, so agents started within the same minute would share the short id (found 2026-09-27; `started_at` keeps the order). The host's session id is kept alongside but never used as the identity: it collides across hosts and is missing on several (`research.md` §26). A worktree is bound to one agent by the git lock reason `<owner> \| <task-id> \| <date> \| agent <uuid>` (the old 3-field form stays valid) and a store row; the lock is the source of truth. Every host gets the same root, naming, lock, list, remove and gc: Claude Code redirects its own worktrees through `WorktreeCreate`/`WorktreeRemove` (T159), hosts with a post-create script adopt theirs (T289), all others use the skill and the MCP tools. Messages between agents and from the user are local, capped, framed as information from another agent and never as instructions. |
| D35 | **Smart memory is on by default for every agent, from one event module (T291–T293, creator 2026-09-27).** `prompt_recall` (5), `startup_recall`, `handoff` and `spawn_brief` default on. This overrides the T131 gate that would have left `spawn_brief` off until a measured net saving. Bodies stay out of the always-on prompt; the index names `mem_get`. One module under `src/agents/` generates every host manifest from that host's event-name map; a host with no equivalent event is MCP-only for it. Hook path stays sync, ≤10 ms, fail-open, no LLM (D13). No vector read while `[plugins.memory.embed] enabled` is false (the default). T454: when `enabled` and `hybrid` are both true, `prompt_recall` reads vectors already stored (`search_notes_hybrid_stored`) and does not call `embed_stale`. SQLite stays the only store (D8). A later private-repo sync is outside this repo; T294 only makes an export row able to carry a tombstone. Evidence: `research.md` §27. T390 shipped the table (`src/agents/hook_events.rs`) and a manifest drift test in place of generating the manifests. |
| D36 | **Agent junk deletes only what has evidence; history and credentials are never cleared by default (T338, T339).** `rtok agents junk clear` deletes a path only when `research.md` §22 documents it (official docs or source), when it carries a valid `CACHEDIR.TAG`, or when the user names it in `[agents.junk] extra`; platform cache roots and Electron subfolders without a §22 row are listed read-only with their size, never cleared. Credentials and token files are never read for expiry or deleted. Session history and snapshots are `never` for default and `--include review` runs; `--kind sessions` removes a whole session unit only on hosts whose §22 row documents it and its index; `stale_session_days` defaults to 30. Creator approved (C for T338, C for T339, 30 days) on 2026-10-03. | `research.md` §22.1, §22.2: hosts refresh tokens and prune their own sessions (Claude Code, Gemini: 30 days), sessions share trees with memory and indexes, and undocumented paths (Cursor) sit next to chat history. |

### Architecture

```
                    ┌──────────────── rtok (one binary) ────────────────┐
 Claude Code ──hook─┤ hook <event>  → EventBus → plugins (Pre/Post/...) │
 Cursor/OpenCode ───┤ mcp           → tools: read search tree run expand │
                    │                 mem_save mem_search symbol callers │
 ANTHROPIC_BASE_URL ┤ proxy         → usage capture, live-zone rewrite   │──▶ api.anthropic.com
                    │ stats | bench | doctor | setup | run | expand      │
                    └──────────┬─────────────────────┬──────────────────┘
                         ~/.rtok/rtok.db        ~/.rtok/archive/
```

`Ctx` gives every plugin: the DB handle, the token estimator, the archive store, config, session id. `Measurement { plugin, kind, before_bytes, after_bytes, est_before, est_after, ref_id }` is the only way savings enter the DB.

Plugin catalogue (v0.1). Every plugin is native Rust written from scratch here (D6).

| id | spec (replaces; evidence in research.md) | surface | mechanism |
| --- | --- | --- | --- |
| `measure` | rtk gain, headroom savings, lean-ctx gain | `stats`, `bench`, proxy | session JSONL ingest + proxy `usage`; context-token-turns |
| `cmd` | rtk hook, lean-ctx ctx_shell | PreToolUse(Bash) → `rtok run` | archive raw output; per-family formatters + TOML rules; pointer trailer |
| `read` | lean-ctx ctx_read/search/tree | MCP `read`,`search`,`tree` + PreToolUse(Read) | modes via tree-sitter-tags; re-read dedup |
| `archive` | token-optimizer archive_result, headroom CCR | proxy live zone + `expand` | replace old large `tool_result` with pointer + head/tail |
| `proxy` | headroom proxy | `ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL` | passthrough + SSE; usage capture; never touches system, tools, or last 2 turns |
| `inject` | caveman shrink-hook, ponytail/caveman modes | SessionStart, UserPromptSubmit | budgeted, byte-stable prefix; modes as markdown |
| `guard` | token-optimizer refetch_guard | PreToolUse | identical read/command within N turns → deny |
| `memory` | engram, claude-mem | MCP `mem_save/search/get` | agent-written notes, SQLite FTS5 |
| `graph` | codebase-memory-mcp, serena, codegraph | MCP `symbol`,`callers`,`outline` | tree-sitter-tags index in SQLite; optional LSP is T30.2 |
| `toon` (on by default) | caveman toon, TOON | proxy/MCP | tabular JSON → TOON |

### Working agreement

- Graph backends: do not claim tasks that reintroduce `lbug` / `graph-lbug` / `symbols_lbug.rs` / `grafeo` / `graph-grafeo` / cmake-for-liblbug. Symbol index is SQLite only.
- One task = one branch = one PR (D16). Never skip the Check.
- Read `research.md` §3 (hook contract) before any hook task. Hook input is JSON on stdin; output is JSON on stdout; exit 0. Exit 2 blocks (PreToolUse only). PostToolUse can only add context.
- Fail open: any plugin error → log to DB and return the unmodified input/empty output. A hook that crashes must still exit 0 in ≤ 10 ms.
- No new dependency without a one-line justification in the commit message.
- Don't duplicate code or logic: find the existing helper and reuse it, or extract one shared helper at the responsible layer.
- Code style: `cargo fmt`, `cargo clippy -D warnings`, `cargo nextest run` green before every Check.
- Anything unmeasurable is a bug in the plan: add a `Measurement` before adding a feature.
- Every new CLI flag gets a key in `config/default.toml` and a row in `docs/config.md` in the same commit (D12).
- No plugin shells out to, links, imports from, or reads the data of a third-party tool (D6).
- Every new plugin obeys D21.
---

## Review 2026-09-17 — bug hunt (post #37–#47)

Scope: `origin/main` after cross-platform agent fixes #37–#47. Local WIP from other agents was stashed (`preserve-other-agents-wip-before-docs-review-bugs-plan`) and not reviewed. No code fixes in this pass — findings tracked as T55.x.

### Blockers

None for the macOS/Linux happy path on current main. Windows correctness gaps below are P1.

### Should fix

1. ~~**T55.1**~~ — done in #49 (`display_rel` case-insensitive strip).
2. ~~**T55.2**~~ — done in #49 (`never_wrap` case-insensitive stem).
3. ~~**T55.3**~~ — done in #49 (`file_uri` percent-encoding).
4. ~~**T55.4**~~ — done in #49 (host-shell-safe wrap / cmd quoting).
5. ~~**T55.5**~~ — done in #49 (`search_max_bytes`).
6. ~~**T55.6**~~ — done in #49 (`call` in mcp.cmd).

### Nits

1. ~~**T55.7**~~ — done (`skip_word`; quoted `cd` paths bucket by family).
2. ~~**`expand::parse_range` when start > line count**~~ — done 2026-09-23: a start past the last line errors (`start exceeds line count N`) instead of printing nothing with exit 0.
3. **`guard::strip_wrap`** — updated in #49 for PowerShell `''`.
4. **T55.8 / T55.9 / T55.10** — filed from the T55.7 code read: guard `read:` keys survive a mutating Bash, guard Bash key cwd-blind, three copies of `cmd_stem`.

### Residual still open (called out before)

- T55.1–T55.6 closed by #49.
- PATH / single-quote parsers — rtok: T55.7 closed; larger residual in ketch.
- Guard false denies — T55.8 (P2), T55.9; flag-aware read-only classes promoted from I-38 as T57.1.
- Test VFS migration — D29 / T56.x (`Vfs` helper in #49).
- T48.3 still todo: Cursor plugin mcp.json still bare `rtok mcp`.

### Second pass (2026-09-17, later the same day)

Scope: hook dispatcher/types, guard, cmd (hook/run/rules/formatters), read (mod/cache/hook/search), archive, proxy (mod/wire/anthropic/openai_chat/semantic_cache), expand, store (archive/decisions/read_cache paths), mcp session handling. Four findings reproduced with a scratch integration test (written, run, deleted — `cargo nextest run --test zz_review_repro` → 4 failed exactly as predicted); two filed from code read. No code fixes in this pass — findings tracked as T55.11–T55.16, propositions as I-39/I-40.

- ~~**T55.11 (P2)**~~ — done: `expand` never froze the owning session's pointer (every expand caller runs under session `expand` / `mcp-<pid>`, decisions belong to the proxy session; expand rate stayed 0, toon attribution dead). Reproduced.
- **T55.12 (P2)** — Windows `wrap_quote` `''` quoting is wrong under Git Bash (Claude Code's Windows shell): apostrophes silently dropped from the rewritten command. Code read.
- ~~**T55.13 (P3)**~~ — done: Copilot `Read` inputs use `path`; `guard::cache_key` and `read::hook` match `file_path` only → dedup and advice never fire on that host. Reproduced.
- ~~**T55.14 (P3)**~~ — done: the semantic-cache key ignored tool_result text; two requests differing only in tool results hashed identically and were both eligible under defaults. Reproduced.
- **T55.15 (P3)** — `live_blobs` overwrites image/document `source.data` / `image_url.url` with pointer text → invalid request when the flag is on. Reproduced.
- ~~**T55.16 (P3)**~~ — done: the guard deny read the full archived body (and estimated over it) on the ≤ 10 ms PreToolUse path. Code read.

### Out of scope this pass

Concurrent agent WIP on local `main` (stashed as `preserve-other-agents-wip-before-docs-review-bugs-plan`). Half-finished T48–T53 cards on that WIP were not judged as shipped bugs.

---

## Note 2026-09-17 — testing library candidates

Shared catalog: [`listepo/rust.md`](../../rust.md) → *Testing candidates*.
Catalog only — no blanket `Cargo.toml` adds (Working agreement: justify each dep).

Fits for rtok (1–3):

1. `vfs` (crates.io) — evaluate against in-house T56 `src/testutil.rs` `Vfs`
   before adopting; mandate stays "prefer VFS over host TempDir".
2. `mockall` — trait mocks for provider / plugin host seams when hand fakes
   get noisy (`httpmock` stays for HTTP).
3. `tokio-test` — async unit helpers beyond `#[tokio::test]` where time/task
   control matters.

Already covered: `assert_cmd`, `divan`, `httpmock`, `insta`, `rstest`,
`trycmd`, `similar`. Skip `test-case` / `expect-test` / `mockito` duplicates;
`testcontainers` / `bolero`/`honggfuzz` only if a measured e2e/fuzz gap appears.

---

## Note 2026-10-02 — Empryo-derived tasks (T368–T378)

Source: study of [proxysoul/Empryo](https://github.com/proxysoul/Empryo) (formerly SoulForge) at `669ff91`. **Idea-only, clean-room: Empryo is BSL 1.1, no code copied.** Every card cites Empryo only for the idea; implementations are written from the card.

Take first, in order: T369, T370, T372, T373, T374. Then T375, T377, T378 (gated on I-95). T368, T371 and T376 are done.

The 14 portable ideas and where each landed:

1. PageRank repo map — T370 (extends T52.3 / I-28).
2. Edge confidence / name IDF — T368.
3. Git co-change — T371 (done).
4. Blast radius under a budget — T377.
5. Trigram index — T378 (gated on I-95).
6. Clone detection (MinHash) — not now: T52.4 / I-29 (dead code) has no measured question that duplicates answer, and Empryo's own perf PR #220 shows the cold-scan cost; revisit after T68.9.
7. Grep → symbol intercept — T369 (builds on I-08 / T50.4).
8. Post-edit diagnostics delta — does not fit: rtok does not run edits; it is the agent loop's job (cox).
9. Backend fallback / LSP hygiene — T376.
10. Compound tools (rename, project check) — does not fit: they need the agent's edit loop and approvals; rtok stays read/measure.
11. Deterministic compaction state — T375 (checkpoint); LLM compaction itself is the host's.
12. Memory RRF / file affinity — T373, T374.
13. Edit robustness (fuzzy `old_string`) — does not fit now: I-43 / T58.3 measured 1.3 % `old_string` misses; below the gate.
14. Shell output compress / tee — already covered by the `cmd` rules (`src/plugins/cmd/rules.rs`) and the archive (`expand <id>`).

### T441. Task adapters: agents create and track tasks through rtok, stored on disk, in GitHub or in GitLab (epic)

Creator request 2026-10-07 (voice). Plan only: no code until the creator approves the design that comes out of T441.1. Approved 2026-10-07 after T441.1: own core (§12); the remaining §12 questions are settled in the subtask they affect. Every subtask below builds on the T441.1 research findings.

Check: T441.1's findings are recorded in `research.md` and the creator approves the design; each later subtask closes only when its §10 tests and `just check` pass.

#### Goal

Agents in any project create, read and close tasks only through rtok (AirTalk). rtok intercepts task creation, hands out a unique task number from one allocator, and stores the task through the project's configured **adapter**: plain files on disk, GitHub Issues + Projects, or GitLab Issues. Many agents work in parallel, so numbering must never collide. When a task is done, the adapter archives it (disk) or moves it to a done state (GitHub/GitLab) so it no longer shows up in the plan.

#### Terms

- **Provider** — already taken in rtok: an upstream LLM API the proxy talks to (`src/proxy/anthropic.rs`, `openai_chat.rs`, `gemini.rs`). Not reused here.
- **Adapter** — new: a task storage backend (`disk`, `github`, `gitlab`). One adapter per project, chosen in config. Lives in a new `tasks` module (or crate `crates/rtok-tasks`), never under `proxy`.
- **Task** — `id`, title, description, status, optional parent. **Plan** — the list of tasks that are not done.
- **Allocator** — the single per-machine component that hands out task ids.

#### 1. Research (T441.1, first; everything else follows it)

Findings so far (verified 2026-10-07; T441.1 finishes them with a short `research.md` section):

| Prior art | Storage | IDs under parallel agents | Surface | Statuses | GitHub/GitLab sync | What we take |
| --- | --- | --- | --- | --- | --- | --- |
| [Backlog.md](https://github.com/MrLesk/Backlog.md) | One Markdown file per task in `backlog/`, config `backlog.config.yml` | Sequential with a configurable prefix (`TASK-1`), subtasks `TASK-5.3`; allocation under `withCreateLock()`, counting other worktrees' uncommitted files (per repo) | CLI first (`backlog task create/list/edit`, `--plain`/`--json`), optional stdio MCP (`backlog mcp start`) over the same core ([MCP README](https://github.com/MrLesk/Backlog.md/blob/main/src/mcp/README.md)) | Configurable, default To Do / In Progress / Done; archive folder | None built in | Markdown-file disk layout, configurable prefix, one core with CLI + MCP as thin wrappers, `claude mcp add` / `codex mcp add` one-liners, agent instructions pointing at a workflow doc |
| [Taskmaster AI](https://github.com/eyaltoledano/claude-task-master) | `.taskmaster/tasks/tasks.json` | Numeric per tag, subtasks `1.2`, `1.2.1` ([task structure](https://github.com/eyaltoledano/claude-task-master/blob/main/docs/task-structure.md)); read-modify-write of one JSON file under its own `.lock` file (`withFileLock`) | CLI `task-master list/show/set-status` and MCP `get_tasks`, `get_task`, `next_task`, `set_task_status`; tool tiers via `TASK_MASTER_TOOLS` to keep tool lists small | pending, in-progress, done, review, deferred, cancelled | None built in | Dotted subtask ids, `next` command, small default MCP tool set; avoid one JSON file per repo as the only state |
| [beads](https://github.com/steveyegge/beads) | Dolt (versioned SQL) per repo | Hash ids (`bd-a1b2`) from content + time + creator + nonce, retried on collision, length grows with the DB ([FAQ](https://github.com/steveyegge/beads/blob/main/docs/FAQ.md)); hierarchical `bd-a3f8.1` | CLI `bd` with JSON output | open, in progress, blocked, closed | `bd github`, `bd gitlab`, `bd linear`, `bd jira`, … with pull/push/sync | The reason sequential ids fail across branches/machines; we keep human `R12` ids from one allocator but adopt its import + external-id mapping and "same id = update" rule |
| [GitHub MCP server](https://github.com/github/github-mcp-server) | GitHub | GitHub's own per-repo issue number | MCP toolsets `issues` (`issue_read`, `issue_write`, `list_issues`, `sub_issue_write`) and `projects` (`projects_get/list/write`, needs a PAT with `project` scope) | open/closed + Project Status field | Native | The GitHub adapter can call the same REST/GraphQL endpoints; sub-issues map to `R2.1`; Projects need the `project` scope |
| [Linear MCP](https://linear.app/docs/mcp) | Linear (hosted) | Linear's team key + number (`ENG-123`) | Remote MCP over Streamable HTTP at `mcp.linear.app/mcp`, `save_issue` create-or-update | Team workflow states | Native | Upsert tool shape (`save` with/without id); hosted ids keyed by a short team prefix, like our project prefix |
| [Claude Code tasks](https://code.claude.com/docs/en/agent-sdk/todo-tracking) (TodoWrite; TaskCreate/TaskGet/TaskUpdate/TaskList) | Session or shared list (`CLAUDE_CODE_TASK_LIST_ID`, [env vars](https://code.claude.com/docs/en/env-vars)) | Host-internal | Built-in tools only | pending, in_progress, completed | None | Agents already think in these three statuses; our status names map 1:1 |
| [Codex CLI `update_plan`](https://github.com/openai/codex/blob/main/codex-rs/protocol/src/plan_tool.rs) | In-session only | None (whole plan resent each call) | Built-in tool | pending, in_progress, completed; one step in progress | None | Same status vocabulary; our tasks are durable and shared, theirs are a per-turn checklist — keep both, do not replace |
| [AGENTS.md](https://agents.md/) | Markdown convention | — | — | — | — | Install writes a short `AGENTS.md`/`CLAUDE.md` rule: "create and update tasks only through `rtok task`" |

Re-checked in T441.1 (`research.md` §35.1): Backlog.md and Taskmaster lock id allocation per repository and beads uses hash ids, and beads already syncs GitHub and GitLab. What none has is one counter per project shared by every checkout, worktree and agent host on the machine, served through the MCP server and CLI the agents already have — that is what rtok adds, and §12 asks whether it is worth building rather than adopting beads.

T441.1 done means: the table above re-checked, plus answers to the open questions it can settle (GitLab work items API vs issues, GitHub sub-issues limits, rate limits), recorded in `research.md`; the design below adjusted to the findings.

#### 2. Recommended architecture: one core, thin CLI and MCP front-ends

- One core (`rtok_tasks`): domain types, allocator, adapter trait, the three adapters. No I/O policy in the front-ends.
- **CLI** `rtok task …`: for agents that prefer shell (Codex CLI, Claude Code with Bash) and for humans and scripts. `--json` on every command.
- **MCP**: new tools on the existing `rtok mcp` stdio server (no second server, no second install): `task_create`, `task_list`, `task_get`, `task_status`, `task_next`. Small tool set, like Taskmaster's core tier. Same JSON shapes as the CLI.
- **Transport**: stdio for every host (Claude Code, Claude Desktop, Codex CLI, Codex app/IDE all speak it). No HTTP listener.
- **Install/config** (through the existing `rtok agents install <host>`; entries already managed, host configs touched only at our entry):
  - Claude Code: `claude mcp add rtok --scope user -- rtok mcp` (or the plugin).
  - Claude Desktop: `claude_desktop_config.json` → `"mcpServers": {"rtok": {"command": "rtok", "args": ["mcp"]}}` (absolute path to `rtok`, since the app has no shell PATH).
  - Codex CLI and Codex app/IDE share `~/.codex/config.toml`: `[mcp_servers.rtok]` `command = "rtok"`, `args = ["mcp"]` (or `codex mcp add rtok -- rtok mcp`).
  - Every host: the AGENTS.md/CLAUDE.md rule line from §1.
- **Many processes at once**: every Claude/Codex window spawns its own `rtok mcp` and CLI calls are their own processes, so there is no in-memory singleton. The singleton is the allocator state file under rtok's data dir guarded by an OS file lock (§5); all processes go through it. Optional later: route allocation through the existing hook resident (`src/hooks/resident.rs`) to save a lock round trip.

#### 3. CLI commands (proposed names)

- `rtok task create "<title>" [-d <text>|--body-file <path>] [--parent R2] [--status open]` → prints the id (`R12`, `R2.3`).
- `rtok task list [--status open,in-progress] [--all] [--json]` → the plan (done tasks hidden unless `--all`).
- `rtok task show <id> [--json]` → title, description, status, parent, subtasks, external link.
- `rtok task status <id>` → prints status; `rtok task status <id> <open|in-progress|done>` → sets it (`done` triggers §8).
- `rtok task next` → first open task without an open parent dependency.
- `rtok task init [--adapter disk|github|gitlab] [--prefix R]` → writes project config.
- `rtok task sync` → reconcile local cache/index with the adapter (GitHub/GitLab).

#### 4. Data model

- `TaskId { prefix: String, path: Vec<u32> }` — `R12` = prefix `R`, path `[12]`; `R2.1` = `[2, 1]`. Parse/print round-trip; case-insensitive input, canonical upper-case output.
- `Task { id, title, description, status, parent: Option<TaskId>, created_at, updated_at, external: Option<ExternalRef> }`.
- `Status { Open, InProgress, Done, Closed }`: `Closed` is "won't do"; both it and `Done` leave the plan (T441.2, `src/tasks/mod.rs`). Maps to Claude Code / Codex `pending/in_progress/completed`.
- `ExternalRef { adapter, number_or_iid, url, node_id }` — the GitHub/GitLab issue the task lives in.
- Allocator state: `{ project_key → { prefix, next_top: u32, next_sub: map<top, u32> } }`.

#### 5. ID allocation

- One allocator per machine: a Diesel table in rtok's existing store (T441.1, `research.md` §35.4). Allocation is one `exclusive_transaction` (read → increment → write); the store already runs WAL with `busy_timeout`, so it is atomic across processes and crash-safe on macOS, Linux and Windows without a second file or lock.
- The project key is the `origin` remote as `host/owner/repo` (two clones share it), else the main checkout's path, never the prefix, so two projects with the same first letter keep separate counters (T441.3, `project::project_key`).
- Prefix: first letter of the project name, upper-cased (rtok → `R`), overridable in config. Two projects with the same prefix are allowed (counters are per project) but `rtok task init` warns; a two-letter override is recommended for cross-project references.
- Subtasks: `R2.1`, `R2.2` from the parent's own sub-counter; depth limit 2 at first.
- Seeding: on `init` for an existing tracker the counter starts above the highest id already present (scan disk files / GitHub titles / labels).
- GitHub/GitLab: the rtok id is ours, the issue number is theirs. Issue title is prefixed `R12. <title>` and carries label `rtok:R12`; `ExternalRef` stores the mapping. Never derive our number from theirs (PRs share GitHub's counter).
- Different machines: one allocator per machine cannot stop two machines issuing the same `R13`. Adapter `create` checks for an existing `rtok:R13` and on conflict re-allocates past the highest remote id (GitHub/GitLab label search is the cross-machine source of truth). Open question whether that is enough.

#### 6. Adapter trait

```rust
trait TaskAdapter {
    fn create(&self, task: &NewTask, id: &TaskId) -> Result<Task>;
    fn list(&self, filter: &Filter) -> Result<Vec<Task>>;
    fn get(&self, id: &TaskId) -> Result<Option<Task>>;
    fn set_status(&self, id: &TaskId, status: Status) -> Result<Task>;
    fn archive(&self, id: &TaskId) -> Result<()>; // done flow, §8
    fn max_id(&self, prefix: &str) -> Result<Option<TaskId>>; // seeding, collisions
}
```

As built in T441.4 (`src/tasks/adapter.rs`): `archive` is folded into `write_status` (done/closed archive), and the free `set_status` guards parents for every adapter.

Sync and blocking HTTP is fine for a CLI; errors carry the adapter name and the external URL. No adapter shells out to `gh`/`glab` unless T441.1 decides to (token handling).

#### 7. Adapters

- **disk** (default, offline): one Markdown file per task under `tasks/` in the project, `R12 - <slug>.md` with a small front matter (id, status, parent) and the description as body, the Backlog.md layout. Writes are temp + rename. Optionally renders `plan.md`/`todo.md` rows in this workspace's format (open question).
- **github**: issues via REST; status via a Projects v2 Status field (`Todo` / `In Progress` / `Done`) via GraphQL; subtasks as sub-issues (100 per parent; REST takes the sub-issue's database id). Token from `GH_TOKEN`, then `GITHUB_TOKEN`, then `gh auth token`; Projects need the `project` scope, and user-owned projects a classic token. Writes are paced (500 content-creating requests/hour, one per second). Config names the owner/repo and project number; field and option ids are looked up once and cached.
- **gitlab**: issues via REST v4 (`/projects/:id/issues`), done = closed. Subtasks are tasks under the issue, linked through the GraphQL work item hierarchy (REST has no parent field). Status: native Status through GraphQL on Premium/Ultimate 18.4+, else a `status::in-progress` label the adapter swaps itself (scoped labels are Premium too). Token `GITLAB_TOKEN`, scope `api`; self-hosted base URL in config.

#### 8. Done / archival flow

`rtok task status R12 done` (or MCP `task_status`):
- disk: file moves to `tasks/done/` (or is appended to `done.md` and removed), so `list` never returns it again.
- github: Project Status → `Done` and the issue is closed (`state_reason: completed`); `list` filters done out.
- gitlab: issue closed, status label set to done.
- Parent with open subtasks cannot be done (error lists them) unless `--force`. The id is never reused.

#### 9. Config

Project `.rtok.toml` (rtok's own TOML, schema from types, one config module — T238; the `[tasks]` section landed in T441.2):

```toml
[tasks]
adapter = "github"        # disk | github | gitlab
prefix = "R"              # default: first letter of the project name
[tasks.disk]
dir = "tasks"
[tasks.github]
repo = "pyrlyn/rtok"
project = 7               # Projects v2 number; optional
[tasks.gitlab]
url = "https://gitlab.com"
project = "group/name"
```

#### 10. Tests to write

- `TaskId` parse/print round-trip, ordering, invalid input.
- Allocator: N processes × M allocations in parallel (spawned test binaries) → all ids unique and dense; crash between write and rename leaves a valid state; lock timeout reports clearly.
- Two projects with the same prefix keep separate counters; seeding starts above the existing max.
- disk adapter: create/list/get/status/archive on a temp dir; done tasks never listed.
- github/gitlab adapters against recorded HTTP fixtures (no live network in CI, no real agents in tests): create maps title/label, status moves the Project field, done closes; collision re-allocation.
- CLI ↔ MCP parity: same inputs give the same JSON.
- Install: host config entries for Claude Code, Claude Desktop, Codex stay byte-for-byte except our entry.

#### 11. Milestones

1. **T441.1 Research** — finish §1, record in `research.md`, settle what it can of §12, adjust this card.
2. **T441.2 Core types** — `TaskId`, `Task`, `Status`, config section; unit tests.
3. **T441.3 Allocator** — locked file (or Diesel table), project key, prefix rule, subtask counters, seeding hook; the parallel-process test.
4. **T441.4 Adapter trait + disk adapter** — trait, disk layout, archive on done.
5. **T441.5 CLI** — `rtok task create/list/show/status/next/init`, `--json`.
6. **T441.6 MCP tools** — `task_*` on `rtok mcp`, parity test. The server entry is already what `rtok agents install` writes for every host, so the tools need no install of their own.
7. **T441.7 GitHub adapter** — issues, sub-issues, label mapping, collision check. Done; Projects v2 Status split into T441.11, `sync` into T441.12.
8. **T441.8 GitLab adapter** — done: issues, `status::` labels, close on done, self-hosted https URL, `relates_to` parent link (#819).
9. **T441.9 Docs** — README/docs section in English with `docs/ru|uk` synced (CONTRIBUTING.md), `toolchain.md` for any new dependency.
10. **T441.10 Instruction line** (split from T441.6) — the AGENTS.md/CLAUDE.md rule line from §1 installed through `rtok agents install`, host config entries byte-for-byte except ours (§10).
11. **T441.11 GitHub Projects v2 Status** (split from T441.7) — done: new issues join the `[tasks.github] project` board and its Status follows the task (#817).
12. **T441.12 `rtok task sync`** (split from T441.7) — done: counters raised to what the adapter holds, drift reported, the adapter never written (#820).

#### 12. Open questions

- ~~Build or adopt (from T441.1): beads already has hash ids, a CLI, an MCP server and GitHub/GitLab sync; Backlog.md has the Markdown layout and per-repo locked ids.~~ Creator decision 2026-10-07: build rtok's own core as this card designs it (human `R12` ids, one counter per project in the store, `rtok mcp` + CLI, disk/GitHub/GitLab adapters); no beads or Backlog.md dependency.
- ~~AirTalk's own prefix: the creator's example is "AirTalk → R" (the binary is `rtok`); the product name starts with `A`. Keep `R` for this repo by override?~~ Creator decision 2026-10-07: `A` for this repo. T441.5's `rtok task init` writes `[tasks] prefix = "A"` into `.rtok.toml`; not committed earlier, since installed rtok builds older than T441.2 reject an unknown `[tasks]` table there.
- ~~Allocator in a JSON file + lock or in rtok's SQLite store?~~ Settled by T441.1: the store (§5).
- Is per-machine allocation plus the remote label check enough for several machines, or should GitHub/GitLab adapters allocate remotely (e.g. a counter issue)?
- ~~Does `closed`/won't-do need its own status, distinct from `done`? T441.1: both providers have it natively (GitHub `not_planned`, GitLab "Won't do"), so it maps cleanly; recommended yes.~~ Creator decision 2026-10-07: yes, `Status::Closed` (T441.2).
- Should the disk adapter also keep this workspace's `plan.md`/`todo.md`/`done.md` in sync, or replace them?
- How are tasks that agents create outside rtok (directly in GitHub) adopted — import on `sync`?

#### 13. Out of scope

- Implementation before the creator approves the T441.1 outcome.
- More adapters (Linear, Jira) — later, through the same trait.
- A web UI page for tasks, dependencies beyond parent/child, assignees, priorities, LLM-generated task breakdowns (Taskmaster's PRD parsing).
- An HTTP MCP transport or a hosted service.
- Replacing hosts' built-in TodoWrite/`update_plan` checklists.
