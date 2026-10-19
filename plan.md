# rtok

https://github.com/pyrlyn/rtok

Token-reduction CLI for AI coding agents: hooks, MCP server, API proxy; measured reductions, pluggable methods.

## Security audit findings (2026-10-14)

Find-only security review of `main` at `cd9644881` (OWASP Top 10, injection, path handling,
access control, data at rest, CI supply chain). Nothing was fixed or exploited. Line numbers are
as of that commit. Overlap with the closed audit PR #601 (config validation, `filter`, `memory
import`, exit codes): none; these are new. To take one, give it a T-id, a row and a `Check:` line.

| # | Severity | Finding | Where |
| --- | --- | --- | --- |
| S1 | high | `rtok mcp --http` started in `/` or `$HOME` lets a token holder read any file there | `src/plugins/read/mod.rs:212`, `src/mcp/http.rs:110` |
| S2 | medium | Dashboard WebSocket has no authentication; any local user (or LAN host if bound wide) can change config, run `doctor --fix` and clear junk | `src/web/mod.rs:226`, `:259`, `:433` |
| S3 | medium | `~/.rtok` store, archive and logs are world-readable (prompts, tool output, file contents) | `crates/rtok-store/src/handle.rs:18`, `crates/rtok-store/src/lib.rs:444` |
| S4 | medium | `read`/`search` (also over HTTP) serve secrets inside the project: `.env`, `.git/config`, keys | `src/plugins/read/mod.rs:212` |
| S5 | low | Proxy `/live` and `/health` answer without auth; with `proxy.bind = 0.0.0.0` they leak models and paths | `src/proxy/mod.rs:255` |
| S6 | low | `host_authority` takes the last comma-separated `Host` value | `src/web/mod.rs:301` |
| S7 | low | Resident hook socket is chmod-ed after `bind`, a short window at the umask mode | `src/hooks/resident.rs:71` |
| S8 | low | `rtok mcp --http` is plain HTTP; the bearer token crosses the network in clear unless a TLS proxy fronts it | `src/mcp/http.rs:110` |

### S1. HTTP MCP `read` exposes the whole launch directory

The path guard allows everything under the process cwd (`resolve_with`). `walk_root_ok` refuses
`/` and `$HOME` for walks (`search`, `tree`), but a single-file `read` has no such check. A
`rtok mcp --http` started from a home directory (launchd, a service unit, Claude.app's `/`) lets
any holder of the bearer token read `~/.ssh/id_*`, `~/.aws/credentials`, `~/.rtok/rtok.db`.
Impact: remote file disclosure for the HTTP surface (`http_tools` includes `read` by default).
Fix: refuse to start `--http` when cwd is unwalkable unless `allow_paths` names a project, and
apply `walk_root_ok` to `read` roots on the HTTP surface.

### S2. Dashboard WebSocket is unauthenticated

`origin_allowed` returns `true` when no `Origin` header is sent, which covers every non-browser
client. The socket accepts `set` (allow-listed config keys), `doctor` `apply` (writes host
configs), `junk` `apply` (removes files), `expand` (archived prompt and tool bodies), `graph` and
`export`. On a shared machine any local user can reach `127.0.0.1:3333`; with `web.host =
0.0.0.0` any LAN host can. The browser side (CSRF, DNS rebinding) is handled well.
Impact: broken access control, data disclosure and destructive actions as the owner.
Fix: issue a per-run random token (printed in the URL like Jupyter), require it on `/ws`, refuse
non-loopback `web.host` without it.

### S3. Data at rest is world-readable

`create_dir_all` and the SQLite/archive writers use the umask. On the author's Mac: `~/.rtok`
0755, `rtok.db` 0644 (917 MB with `call_io` request/response bodies), `archive/` 0755,
`errors.log` 0644. Other local accounts can read prompts, code and tool output.
Impact: data leak on multi-user hosts and in backups. Fix: create `~/.rtok`, the archive dir and
log dir with 0700 and files with 0600; `rtok doctor` warns on and fixes looser modes.

### S4. No secret-file deny list on `read`

Nothing stops `read` from returning `.env`, `.env.*`, `.git/config` (embedded tokens), `*.pem`,
`id_*`. Over stdio the host's own tools can read them too, but the HTTP surface is meant for
remote callers, and archived copies then land in the world-readable store (S3).
Fix: a default `plugins.read.deny_globs` (overridable), enforced for HTTP always.

### S5. Proxy status routes without auth

`/live` returns method, path, provider and model of recent calls; `/health` returns config
flags. Harmless on loopback, a small leak when `proxy.bind` is widened. Fix: serve them only on
loopback peers, or behind the same token as S2.

### S6. Ambiguous `Host` handling

`host_authority` splits on `,` and keeps the last part. A repeated or comma-joined `Host` is not
a valid request; picking one part can let a crafted request pass the Origin/Host equality.
Fix: reject a `Host` containing `,` (400).

### S7. Socket mode set after bind

`UnixListener::bind` creates the socket with the umask mode and `set_permissions` runs after.
Fix: create it inside a 0700 directory (or set umask around bind).

### S8. Plain-HTTP bearer token

The docs should say that `--http` on a non-loopback address must sit behind TLS; better, refuse
a non-loopback bind without `public_url` using `https`.

### Not run or blocked

`cargo audit` could not load `~/.cargo/advisory-db` (directory present but broken; left as is).
Tests and lints were not re-run (find-only, no code changed). CI workflows were reviewed:
`graph-review-comment.yml` handles untrusted artifacts well (size caps, PR head SHA check).

### Improvement ideas (opinion)

- I-S1. A single "exposure" check in `rtok doctor`: wide binds, missing tokens, file modes.
- I-S2. Pin GitHub Actions to commit SHAs in `release.yml` (currently tags like `@v6`).
- I-S3. Split `~/.rtok/archive` into hashed subdirectories; it holds tens of thousands of files
  in one directory, slow to list and back up.
- I-S4. Retention for `call_io` bodies by default (the store reached 917 MB here).
- I-S5. A `security.md` threat model naming each surface (hooks, stdio MCP, HTTP MCP, proxy,
  dashboard) and who is trusted on it, so new routes get a checklist.

## Cloud review findings (2026-10-08)

New bugs, dead code and moves from a read-only Cursor cloud review of `main` at `a060af58` (agent `bc-e07c8099-22ee-5d00-b1c7-3bd1ad623d52`; full report: `cloud/rtok.md` in the private `listepo/roadmap` repo). They take ids T457–T470, ordered P0, P1, P2, P3. **confirmed** means seen in the tree; **suspected** means plausible from the code but not proven (nothing was run on Windows). Line numbers are as of the review. The report's own labels T436–T449 are roadmap ids, not this plan's. T457, T458 and T459 are done (`done.md`). None of the rest is in the task table yet: to take one, add its row and a card with a `Check:` line.

| ID | Priority | Kind | Status | Where | Fix |
| --- | --- | --- | --- | --- | --- |
| T464 | P2 | move | compared | `crates/rtok-agent-sdk/src/lib.rs:84-163` (`backup`) → crates `file-backup` | Not a drop-in (checked 2026-10-10): rtok writes to a `_backup/` folder, keeps N generations (`prune_backups`, `stale_backups`) and dedups against any file there; `file-backup` 0.1.1 writes beside the file and never prunes. The creator chose to extend `file-backup` first (crates roadmap T12, with its T4 symlink fix, which the open crates PR #34 also touches), then depend on it here. |
| T465 | P2 | move | compared | `tools/dist-generate.sh`, `tools/release.sh` → `pyrlyn/ci` | Not shared copies (diffed 2026-10-10): `dist-generate.sh` differs from ketch's and swarfr's in 239 and 102 lines; `release.sh` differs from ketch, cox, runa and swarfr in 130, 121, 63 and 75 lines. Nothing to fold until the repos agree on one release flow; no move without a new creator OK. |
| T466 | P2 | move | compared | `.github/actions/rustup-toolchain-cache` → `pyrlyn/ci/.github/actions/` | Only rtok uses it (`ci.yml`, `graph-review.yml`; checked 2026-10-10 across `apps/` and `packages/`). `pyrlyn/ci` already ships `setup-rust` (mise-pinned Rust and `rust-cache`); sharing would mean folding the toolchain cache into it. No move without a new creator OK. |
| T470 | P3 | move | suspected | `tools/test-changed.sh`, `tools/selective-check.sh` → `scoped-check` / `pyrlyn/ci` | Overlap was claimed but not diffed against cox or `pyrlyn/ci`. Compare first; move only what matches. |

Already tracked here, not added again: `src/render.rs` → `change-preview` is T416.1; per-host MCP code → `crates/rtok-mcp` and the unused `rtok_mcp::ops::apply` are T277; `OPERATION_ICONS` → a shared icon crate is T436.3; the test-only `VersionFile::write`/`::new` and the stale `#[allow(dead_code)]` on `read_installed` (`src/agents/plugin_version.rs:84`, `:112`, `:268`) are open on T279.

| # | Status | Priority | Complexity | Readiness | Agent |
| --- | --- | --- | --- | --- | --- |
| T124 | todo | P3 | 2 | 60% | |
| T131 | todo | P2 | 3 | 70% | |
| T156 | in progress | P3 | 3 | 50% | Claude Code / claude-opus-5-5 |
| T261 | in progress | P2 | 3 | 95% | Cursor / grok 4.7 |
| T275 | in progress | P1 | 4 | 90% | Claude Code / sonnet-5.5 |
| T275.1 | in progress | P2 | 3 | 80% | Cursor / grok 4.7 |
| T277 | in progress | P2 | 5 | 30% | Claude Code / sonnet-5.5 |
| T278 | in progress | P1 | 3 | 90% | Claude Code / claude-opus-5-5 |
| T279 | in progress | P1 | 5 | 90% | Claude Code / claude-opus-5-5 |
| T281 | in progress | P1 | 3 | 70% | Claude Code / claude-opus-5-5 |
| T289 | in progress | P2 | 4 | 75% | Claude Code / sonnet-5 |
| T356 | in progress | P1 | 2 | 5% | Claude Code / claude-opus-5-5 |
| T369.1 | todo | P3 | 1 | 0% | |
| T370 | in progress | P1 | 4 | 90% | Claude Code / sonnet-5.5 |
| T385 | in progress | P1 | 5 | 20% | Claude Code / opus-5-5 |
| T385.3 | todo | P1 | 3 | 20% | |
| T385.9 | in progress | P3 | 5 | 70% | Claude Code / claude-opus-5-5 |
| T385.9.3 | todo | P3 | 2 | 10% | |
| T394 | todo | P2 | 2 | 20% | |
| T395 | todo | P3 | 2 | 20% | |
| T396 | todo | P3 | 2 | 90% | |
| T398 | todo | P3 | 1 | 30% | |
| T413 | todo | P2 | 3 | 0% | |
| T413.13 | in progress | P3 | 2 | 0% | Claude Code / claude-sonnet-5-5 |
| T414 | in progress | P1 | 4 | 20% | Claude Code / opus-5.5 |
| T414.7 | in progress | P3 | 1 | 0% | MiMo / mimo |
| T416 | in progress | P1 | 3 | 70% | Claude Code / claude-opus-5-5 |
| T428 | in progress | P2 | 3 | 85% | Claude Code / sonnet-5.5 |
| T436.4 | todo | P3 | 2 | 90% | |
| T522 | in progress | P2 | 2 | 0% | MiMo / mimo |
| T523 | in progress | P2 | 2 | 0% | MiMo / mimo |
| T525 | in progress | P2 | 3 | 0% | MiMo / mimo |
| T526 | in progress | P2 | 2 | 0% | MiMo / mimo |
| T666.2 | todo | P2 | 3 | 0% | |
| T687 | todo | P2 | 4 | 0% | |








### T666.2. Provider-backed memory extractor (blocked on the creator)

T666.1 ships `rtok memory extract` with a user-configured program as the extractor. A built-in extractor needs creator decisions first: which provider and model, how the key is stored and read, and a per-run cost cap. Once decided: a provider extractor behind the same `Extractor` trait, a docs warning about cost, a measured row in `research.md` (needs a paid key, so the harness skips without one). Not claimed.

Check: a fake-server test of the provider call; the cost cap stops a run; off by default; `just check`.


### T687. Hooks: PreToolUse/PostToolUse p50 to 10 ms

Creator-approved (moved from `roadmap.md`, from T178). Cut another ~10 ms off the §19.2 harness p50 so PreToolUse and PostToolUse reach 10 ms as Claude Code sees them (today `research.md` §19.7: 12.3 / 12.5 ms on `rtok-hook`).

**Plan.** (1) Re-measure first with the §19.2 harness on an idle machine (load average low; if it is high, say so and record no number). (2) Profile the hook path (process start, config parse, store open, plugin dispatch) and remove the largest avoidable cost with a fail-first latency test. (3) Record a dated `research.md` row. The fail-open and byte-stable rules stay.

**Status (2026-10-16).** Not started on code: no idle machine to measure on. The rerun script and the reasoning for the first step are `research.md` §46. Needs a quiet machine (load average near zero), not a creator decision.

Check: p50 as Claude Code sees it is at or under 10 ms for both events on the §19.2 harness, or the measured floor and the reason are written up; `just check`.

### T131. Measure the spawn brief: cost row and on/off re-read share
Rule: a saving that is not a `Measurement` row does not exist, and the brief is a cost first. Needs T128 and T130.2.
Plan: T130.1's hook records a `Measurement` (`plugin: "memory"`, `kind: "brief"`) with the tokens it added (before = 0, after = brief) so the cost shows as negative saving; `rtok stats` `subagents` row splits the re-read share and sub-agent input tokens by "spawned with a brief" (the brief's archive id in the sub-agent's first user message) vs without.
Check: fixture with one briefed and one plain sub-agent asserts the split; after a dated window with the flag on, `research.md` §17 gets the measured net; default flips to on only if net tokens saved > 0 — otherwise the card closes with the number and T130 stays off.
Progress (2026-09-25): the cost row was already recorded by T130.1 (`memory`/`brief`, before 0, after = brief tokens); `tests/hook_spawn_brief.rs` now asserts one row per fired brief and none otherwise. `rtok stats` splits the `subagents` re-read share and input tokens into `brief` / `no brief`, detected by `measure::subagents::SPAWN_BRIEF_MARKER` in a sub-agent's first user message (a `handoff.rs` test pins it inside the brief's `INSTRUCTIONS`); fixture test `briefed_and_plain_subagents_split_the_reread_share`. Left: the dated window with the flag on, the net in `research.md` §17, and the default decision.

Plan: finish only the agent-doable part: confirm the fixture/cost-row tests still pass and add whatever small piece the Progress line lacks. The dated flag-on window, the `research.md` §17 net and the default decision need the creator's paid live run: leave them in the card and set the row back to `todo` at 70 %.

Local attempt (2026-10-15): not possible on LM Studio (`prism-ml/bonsai-27b`): the model fails a single-turn read task, so a briefed/plain sub-agent comparison would only measure noise; nothing recorded in `research.md` §17.

Remaining work needs the creator: the fixture and cost-row tests (`tests/hook_spawn_brief.rs`, `briefed_and_plain_subagents_split_the_reread_share`) are in place and pass; the dated flag-on window, the `research.md` §17 net and the default decision need a paid live run.

### T124. Realized `tools_rewrite` saving as a dated `research.md` row
6.2 % (T59.5) is the ceiling, not a saving: no dated row shows what `[proxy.tools_rewrite]` removes with the default `max_description_tokens = 60`. Precondition, by the creator: turn it on for this machine's proxy for at least 20 sessions. Then sum the `kind = tools_rewrite` Measurement rows against session input for the same window (`rtok stats` / `rtok gain`, dated command in the row), and write one row into `research.md` §2 next to the T59.5 row; update `docs/comparison.md` only if it cites the number. If the realized share is under the 3 % gate, say so in the row and leave the default off.
Check: the row cites the command, date, sessions, before/after tokens and the share; no number in prose without it.

Progress (2026-10-15, `research.md` §2): a local reference run (20 one-request sessions through a scratch proxy to LM Studio) puts the saving at 16.2 K tokens per request (42.0 % of a bare request, API-counted); the row says it is not the gate. Left: the creator's 20 real sessions on this machine's own proxy, and an answer-quality check with truncated built-in tool descriptions. Default stays off.

### T156. Probe: `WorktreeCreate`/`WorktreeRemove` hooks and reflink-seeded `target/`

No product code. Two open questions from `research.md` §18.3–18.4: (1) Claude Code's `WorktreeCreate`/`WorktreeRemove` hooks replace the default create/remove — can rtok own location, naming and the ownership record there, and what do the desktop app and sub-agent `isolation: worktree` actually send; (2) does seeding a new worktree's `target/` by reflink (`reflink-copy`, APFS `clonefile`) save build time and disk after a real task, or does cargo rewrite most of it anyway.

Plan: throwaway hook script (scratch, not committed) that logs the payloads for `claude --worktree`, a sub-agent worktree and the desktop app, and returns a path under `_worktrees/`. For (2): two fresh worktrees of this repo, one seeded with `cp -c -R target`, one cold; record wall time of `just check` and physical disk delta (`df`, not `du` — clones are double-counted) for each. Write the payloads, the numbers and the dated commands into `research.md` §18. `reflink-copy` is a new dependency: adopting it is a creator decision taken on those numbers, not part of this task.

Progress (2026-09-25, `research.md` §18.4 second data point): part (2) measured. Cold `just check` took 185 s and +6.28 GiB; seeded took 371 s and +3.35 GiB. The clone skipped every dependency rebuild (≈ 23 s saved), but T236's `dunnage` pass then compressed the cloned files (≈ 215 s). Parked as I-99, with no follow-up task. Lead (unmeasured, 2026-10-08): the installed `dunnage` 0.1.0 has its own `seed` and `worktree add` subcommands (`dunnage --help`), so seeding may not need `reflink-copy`. Part (1), the hook payloads from `claude --worktree`, a sub-agent worktree and the desktop app, is still open: it needs live sessions of the creator's.

Check: `research.md` §18 gains the hook payloads and a dated table (cold vs seeded: seconds, bytes); T159's card is corrected against the recorded payloads; seeding gets a follow-up task or an `ideas.md` entry from the numbers; no file under `src/` changes.

Execution (2026-09-27): (1) a probe kit in the session scratchpad (never committed), like T281, that logs `WorktreeCreate`/`WorktreeRemove` payloads and returns a `_worktrees/` path; the creator runs it with `claude --worktree`, a sub-agent `isolation: worktree` and the desktop app. The documented payload fields go into `research.md` §18.3 now, with sources. (2) Seeded (`cp -c -R target`) and cold worktrees of this repo: wall time of `just check` and physical disk delta (`df` before/after, not `du`). The cold run only happens with ≥ 30 GiB free; otherwise the row says so. Result: a dated row in `research.md` §18.4.

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
Progress (2026-09-28): PR A and B-D merged for Claude, Copilot, Gemini, Codex, Cursor (#444), VS Code (#455), ZCode (#459), Kimi (#467); Grok is #468. PR E merged (#483, doctor warning). Command Code, the last host that gated MCP on its plugin, is T275.2 (2026-10-11). Left: the creator-machine Check.
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
Progress: PR 1 merged (#437); PR 2 (Claude Code and Desktop) done as T277.1. Next: PR 3, the other hosts.

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

### T522. `read` and `expand` accept the line ranges agents sent this week

Found 2026-10-10 in `~/.rtok/errors.log` and the `logs` table, 2026-10-03 through 2026-10-10: 25 `invalid line range` failures, each a different spelling. `parse_range` (`src/expand.rs`) already strips one pair of surrounding quotes (T172) and reads `a, b` as `a-b` (T353). It still rejects a space around `-` (`420 - 640`, five of the 25), a colon (`580:650`), brackets (`[1, 50]`), two numbers separated by a space (`610 614`), an HTML break (`95<br>`, `490<br>-800`), quotes in the middle (`235"-"270`) and an underscore (`1860_1960`).

Done when: each of those 25 strings parses to the two line numbers in it. A trailing `<br>` with no second number is that one line. A value that is not one or two positive lines still fails with `invalid line range`.

Check: unit tests in `src/expand.rs` for the 25 logged strings; `just check`.

### T523. `worktree add` takes `T504 read-hook-same-path` as an id plus a slug

Found 2026-10-10: 27 `task id must match [a-z0-9]…` errors since 2026-10-06. Every one is an id and words in one argument (`T504 read-hook-same-path`, `M16.5 test toml`, `T330.5.3 junk-stale-worktrees`). `ident` in `src/worktree/add.rs` lower-cases, then rejects the space. Agents pass the id and the slug the way the branch is named, as one string. Nine further adds failed with `already exists: one worktree per task` on a retry of an add that had already landed.

Done when: one argument `T504 read-hook-same-path` plans task `t504` and branch `t504-read-hook-same-path`; spaces in the slug become dashes; a bare id is unchanged. `add` of a path whose branch is already that task returns the existing plan instead of failing. A string that is not an id after the split still fails the existing check.

Check: `plan` tests for the logged `T504 read-hook-same-path`, `M16.5 test toml` and `M16.3 push.rs through the config module`, and for an existing path of the same task; `just check`.

### T525. SubagentStart and PreCompact under the 10 ms hook budget

T428 covers SessionStart. On the live store for 2026-10-03 through 2026-10-10, SessionStart's median is already 3.8 ms (149 of 609 runs over 10 ms; the tail is the quiet-host re-run T428 left open). Two other events miss `[hook] max_ms` on the median. PreToolUse's median is 0.8 ms, so the budget is reachable. About 7,500 `hook … slow` warnings this week are mostly the PreToolUse and PostToolUse tail; that tail is not this task.

| event | n | p50 | p95 | max | over 10 ms | over 1 s |
| --- | --- | --- | --- | --- | --- | --- |
| SubagentStart | 758 | 48.6 ms | 760 ms | 3093 ms | 605 | 24 |
| PreCompact | 189 | 25.7 ms | 206 ms | 2551 ms | 167 | 1 |

Done when: a trace names the work inside SubagentStart and PreCompact. That work is under 10 ms at the median on this store, or the card records why those two events get a higher budget. `tests/latency.rs` covers both.

Check: the latency cases green; five manual runs of each event on the rtok repo print no `slow` warning; `just check`.

### T526. `worktree_list` and `search` when `rtok mcp` was started in the home directory

T356 stops the graph from indexing `$HOME`. The other tools still run git there. Since 2026-10-05, `worktree_list` failed 8 times with `fatal: not a git repository` (the last on 2026-10-10), and `search` failed 5 times with `no project root: rtok mcp runs in /Users/listepo`. A server launched in the home directory has no repository until the client sends `roots/list` or the caller passes a path.

Done when: with cwd = home and a client root inside a repository, `worktree_list` and `search` use that root. With no root they return an error that says to pass a path or open a project, and the text is not git's `fatal: not a git repository`.

Check: an MCP test with cwd = a non-repo and a `roots/list` root; the no-root error text; `just check`.

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

Creator decision 2026-10-11: build it behind a flag that is off by default, tested against a mock upstream; the task closes only after the live Gate P28 bench (T385.9.3, needs the creator's API spend). Split (complexity 5): T385.9.1 client and cache, T385.9.2 asynchronous use in the live zone, T385.9.3 the live bench.

### T385.9.3. Gate P28 live bench for the compressor

Part of T385.9, after T385.9.2. `rtok bench` cost per passed task with the compressor on against the v0.1 lossless path, compressor tokens included, plus the must-keep fixture and `expand` recovery; one dated `research.md` row. Needs the creator's session and API spend (like T394), so an agent cannot run it alone. Green → T385.9 closes and the flag may become a default in a follow-up; red → the flag stays off with its number.

Check: the dated `research.md` row with cost per passed task, pass rate and compressor tokens.

Progress (2026-10-15, `research.md` §16.9): a local attempt on LM Studio (`prism-ml/bonsai-27b`) gave no evidence: the control arm returned an empty answer, and with the flag on the compressor never fired (0 summaries). Left: a bench that can pass its control (a capable model), tasks with more than `keep_turns` turns after the large results, and a compressor arm in `rtok bench`; the paid run still needs the creator.

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

Execution plan (2026-10-12): 1. run the Kilo, Gemini CLI, Copilot CLI and pi readers read-only on this machine's own files and record the observed shapes (no content); 2. a synthetic fixture per confirmed shape; 3. check whether a resumed Copilot session writes cumulative or run-only shutdown totals and stop double counting if needed (test first); 4. date each §30 tag; Grok `signals.json` stays unverified until the creator decides.

Progress (2026-10-12, `research.md` §30): Copilot CLI 1.0.80 — `tokenDetails` is authoritative (it equals `usage` plus the compaction requests), the reader now takes it (`token_details_carry_the_compaction_requests_that_usage_leaves_out`, fail-first), and `inputTokens` including the cache legs is confirmed. pi 0.85.1 — reader sums match an independent sum over 953 entries; fixture for the `reasoning`/`cacheWrite1h` shape; fork id copying confirmed from source, not observed. Copilot resume: the reader keeps the last shutdown per session, cumulative per the SDK source (`github/copilot-sdk` 6be623b, `nodejs/src/generated/session-events.ts`; fail-first `a_resumed_session_counts_its_last_shutdown_not_the_sum_of_all`), not yet seen in a real resumed log. Left: a Kilo message row (this machine's db has none), Gemini CLI (not installed here), Grok `signals.json` (observed keys accepted by the creator 2026-10-13, but grok 1.0.34 wrote no `signals.json` in this machine's 3 sessions, `research.md` §30.7). Rechecked 2026-10-13: still 0 Kilo `message` rows and no Gemini chat files. Kilo done 2026-10-15 (`research.md` §30.1: a real `message` row read through LM Studio, fixture added). Still needs the creator to run one Gemini CLI (not installed here) and one Grok session (xAI-only, not runnable on a local model), then a reader and fixture per shape.

### T398. Probe editors on a `worktree.useRelativePaths` worktree

From `research.md` §18.2 (T157): every non-interactive reader opens a relative-link worktree, but VS Code, Zed, Cursor and lazygit are untested, so the setting stays opt-in. It is the setting that would have prevented the 18 GB orphaned `graph-perf` worktree.

Done means: one dated row per editor in §18.2. If all pass, the `worktrees` skill and `AGENTS.md` gain the setting (T157's Check); if any fails, the failure is recorded and the setting stays off.

Check: the §18.2 table has four dated rows.

### T413. More agent hosts: popular agents rtok does not install into yet

Creator request 2026-10-05: add installers for popular coding agents missing from `src/agents/` (22 hosts on `9d6557c7`). One sub-task per host, one PR each. The list below is a lead, not evidence: every fact a sub-task relies on (binary, config paths, MCP entry format, hook events and payloads, plugin or extension API) is first written into `research.md` with its primary source (vendor docs or the repository's own files, URL plus date checked).

Shared shape for every sub-task, per `architecture.md` "New host" and D21:
1. Research: a `research.md` row for the host with the sources above; if a field is not documented, it stays out of the installer (never guessed).
2. `src/agents/<host>/mod.rs` implementing `Agent` (variants, detection, files, installed modules, apply) and `README.md` with the module table and `## Docs`; registered in `HOSTS` and `host()`; `[setup.<host>]` paths in `config/default.toml`, `src/config/mod.rs`, `docs/config.md`.
3. Reuse first: a host that forks or mirrors an existing one (named per card) shares that host's code through a helper, never a copy. Host configs: check only rtok's entry, keep the rest byte-for-byte; backups and `--dry-run` from `rtok-agent-sdk`.
4. `docs/agents.md` row; `agents_install` matrix row (install twice / remove twice / list) against fake homes, never the real agent.

5. Per-host check: the targeted tests above, `agents_doc` and `host_docs` green, `just check` green; one manual `rtok agents install <host> --dry-run` on this machine if the host is installed here, otherwise say "not installed here" in `done.md`.

Check: every sub-task below is closed in `done.md` with its per-host check.





### T413.13. `rtok agents install trae` — Trae

Trae IDE (ByteDance). MCP; check whether its config mirrors the VS Code layout `src/agents/vscode/` already handles.

Check: the T413 per-host check (step 5) for this host.

Plan: Trae docs for the MCP file. Reuse the VS Code helper only if the key and path match; otherwise a thin host of its own.

Finding (2026-10-12, `research.md` §32.13): Trae's docs name only a project `.trae/mcp.json`, which is the project's, and a user-level `mcp.json` behind the app's Raw Config button with no path. No installer is written until a documented user-level path exists.

### T414. Web dashboard restyle on the brand pack, built from `brand/` sources

Creator request 2026-10-05: a new look for the `rtok web` SPA from the brand pack in `brand/` (tokens, logos, icons, `DESIGN.md`, on top of the `@pyrlyn/brand` base it pins). Creator decisions 2026-10-05: a restyle by the brandbook (pages and navigation stay; shell, panels, tables, KPIs and charts change); a mockup first, approved before the pages; our own `web/src/ui` React components on the brand roles (`--pyr-*`), not the base `.pyr-*` classes. Creator decisions 2026-10-05, later: UI/UX additions (grouped sidebar, command palette and shortcuts, clickable KPIs, live status with pause) and features (Δtok savings trend, table filters in the URL, CSV/JSON export), one sub-task each (T414.8–T414.14). Creator decisions 2026-10-05, later still: charts draw on canvas through ECharts 6, behind our own chart abstraction so the library can be swapped; hover shows a tooltip, and other places change live with the hover where that does not repeat the tooltip; small marks (budget grid, plugin bitset, token mix, share bars) stay DOM and share the same tooltip (T414.15, T414.16). Creator decision 2026-10-05, after the charts: open-source UI toolkits are allowed; behaviour (focus, keyboard, overlays, menus, dialogs, listboxes) comes from React Aria Components, styled by us on the `--pyr-*` roles, so the look stays ours (from T414.9 on; its `Autocomplete` serves the palette, so no `cmdk`).

Source rule: `web/` holds no copy of a brand file. Tokens, fonts, icons, logos and illustrations are imported from `brand/` and `brand/node_modules/@pyrlyn/brand` at build time; anything derived (CSS, raster sizes) is produced by a program in the build, never committed by hand. `brand/README.md` "Known gaps" and `PROVENANCE.md` "Adopting in each surface" list today's copies.

Check: every sub-task below is closed in `done.md`, and no file under `web/` is byte-identical to a file under `brand/` or `brand/node_modules/@pyrlyn/brand/base/`.

### T414.7. Re-shoot `web/screenshots/`; close the web admin gap in `brand/`

Regenerate `web/screenshots/` with the existing script; in `brand/README.md` "Known gaps" and `PROVENANCE.md` "Adopting in each surface" mark the web admin as adopted.

Check: `just check` green; `brand/README.md` no longer says the web admin ships its own copies.

### T416. Shared `change-preview` crate for dry-run output

Every command that changes the disk should preview it the same way, and ketch and cox carry the same need (ketch has its own dry-run paths; cox depends on `similar` and `diffy`). The renderer moves out of `src/render.rs` into a crate with a neutral name in `packages/crates` (`pyrlyn/crates`, tracked there as T1), released to crates.io by that repo's release-plz pipeline; rtok then depends on the crates.io version, because a path outside this repository does not resolve in CI. Blocks T416.1-T416.4.

The crate renders two kinds of change. A file edit (path, before, after) is a unified diff with the `a/` and `b/` headers `render::unified_diff` prints today, or in stat mode a `path | 7 +++--` line; a removal (path, bytes, files) is `- path  12.1 GB  48 213 files`. Both modes end with a totals line: `3 files changed, 12 insertions(+), 4 deletions(-)` for edits, `15 paths, 1 204 311 files, -106.2 GB` for removals. Colour goes through owo-colors' `if_supports_color` exactly as `render::paint` does; byte sizes through the crate's `human_bytes` (the same binary units as `info::human_bytes`); everything derives `Serialize` for `--json`.

Plan: in `packages/crates` (branch `t1-change-preview`), add the crate's project files (`AGENTS.md`, `plan.md`, `todo.md`, `done.md`, `roadmap.md`, `ideas.md`, `toolchain.md`) and the crate as a workspace member, with unit tests for both modes, the totals and the colour switch; `bump.yml` gains a `package` choice and `release.yml` reads the crate from the tag, so the pipeline that releases `file-backup` releases this crate too; one PR there; then `bump.yml -f package=change-preview` publishes it. The crates.io token must allow publishing the new crate name.

Check: `cargo test` and `cargo clippy -- -D warnings` green in `packages/crates`; the crate is on crates.io.

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


Execution plan (2026-10-12): 1. a neutral-name crate in `packages/crates` (own worktree and PR there, its AGENTS.md rules) holding `OPERATION_ICONS`, `icon()`, `ICON_WIDTH` and the gutter padding, generic over the tone/kind type, with unit tests; 2. after release-plz publishes it, rtok's `src/ui/style.rs` and ketch's `src/ui.rs` depend on the crates.io version with no output change; until then this card stays in progress with the crate PR linked.

Progress (2026-10-12): step 1 is done: crate `operation-icons` merged in the crates repository (PR #56) and not yet published. Step 2 waits for the creator to release it (the release pipeline has Actions off there); then rtok and ketch switch to the crates.io version.

### T436.4. Spinner on `agents install/update` through T276's `ProgressRunner`

Split from T436.2 (2026-10-08): T436.2 shipped the remaining waits and the operation icons on the `agents install/update/remove` header, but the card's install/update spinner rests on T276's `ProgressRunner`, which does not exist yet; the existing `with_loader("updating host")` stays until then. Depends on T276.

Check: `agents install` and `agents update` show one spinner per host on a TTY and nothing on a pipe (non-TTY test); trycmd snapshots unchanged; the creator's manual run of `rtok agents install` in a terminal.

Execution plan (2026-10-12): 1. replace `with_loader("updating host")` in the `agents install/update` path with a per-host spinner through `rtok_sys::proc` progress (T276.1/T276.2); 2. TTY only, nothing on a pipe; 3. non-TTY test, trycmd snapshots unchanged; the manual terminal run stays for the creator.

Progress (2026-10-12): `agents install/update` draw one `ProgressRunner::batch` bar stepped once per host (`src/agents/mod.rs`, `host_bar`), off a TTY and while the plugin question may be asked; `apply_hosts` no longer uses `with_loader`. Tests: `agents::tests::apply_all_steps_the_bar_once_per_host` (fail-first), `tests/agents_update.rs::install_and_update_draw_no_bar_on_a_pipe`; trycmd unchanged. A TTY run with `--yes` or `--dry-run` now shows the bar; the restart wait in `restart.rs` has no indicator until T276.3. Left: the creator's manual run in a terminal (bar per host, cleared before the result lines; no bar over the plugin question without `--yes`).

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

### T644. Tests remove the temp dirs they create

The tests leak their temp dirs into `$TMPDIR`: on the creator's Mac about 24,000 `rtok-mem-*` (`Runtime::in_memory` in `src/plugin.rs` creates one per call and never removes it), 6,300 `rtok-tui-*`, 2,000 `rtok-guard-*`, 1,300 `rtok-usage-*` and more. A directory that size makes `realpath` in it slow under load (T643). Each test helper that makes a temp dir removes it (a guard or `tempfile::TempDir`), `Runtime::in_memory` first.

Check: a test shows a dropped `Runtime::in_memory` leaves no directory behind, and a full `just test` run adds no `rtok-*` entry to `$TMPDIR`.

## Beat sqz, lean-ctx, token-optimizer and caveman per category (T672–T686)

Source: `docs/research/competitors-techniques.md` (techniques, licenses, links). Every card: keep 100 % of hashes, paths and WARN/ERROR lines, never more tokens than raw, stderr and exit line kept on failure.

T905 (idea I-133): `podman ps` uses the `docker ps` formatter. Done (`done.md`).
