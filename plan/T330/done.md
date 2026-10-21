Split into T330.1 to T330.7 (one PR each); this card stays the spec and the epic.

Ivan, 2026-10-01: one command group to see and clean junk for every agent: `rtok agents junk list` to view and `rtok agents junk clear` to remove. For each agent, show its folders (as links), the size of each folder in KB/MB/GB, and how much space a clear would free. `clear` stays a dry run by default and deletes only with `--yes`, as T182's `rtok agents junk clear` does today.

Today `rtok agents junk clear` (T182, #286) only clears junk rtok itself owns under its home (rotated `rtok.log.<N>` siblings past `[log] files`, archive payloads past `core.retain_calls_days`). The per-host junk map is research only (`research.md` §22); T182.1 (wire the host folders after review) never landed. There is no `list`, no per-agent view and no sizes.

#### Terms

- **Agent:** each host in `agents::HOSTS` (Claude Code, Cursor, Codex, Gemini, Kimi, ...), plus **rtok** itself as its own row. Where rtok knows agent ids (T282, D34), sessions and worktrees are attributed to the agent id under its host.
- **Agent folder:** a directory an agent writes to: its config/data home (for example `~/.claude`, `~/.cursor`, `~/.codex`), its cache dir (`~/Library/Caches/<app>`, `$XDG_CACHE_HOME/<app>`), its log dir, and per-project folders it creates (for example `<repo>/.claude/`), plus worktrees rtok created for it (T285).
- **Junk:** files that can be deleted without losing user data, settings, credentials or history the user wants, because they are regenerated, re-downloaded or were only temporary. Each junk kind below has a **class**: `safe` (always regenerated, cleared by default), `review` (usually junk, cleared only when named or with `--include review`), `explicit` (user data the user may still want: never in a default or `--include review` run, cleared only when named with `--kind`), or `never` (shown for size only, never deleted). D36 decides which paths may be cleared at all.

#### Junk kinds per agent

| Kind | Examples | Class | Notes |
| --- | --- | --- | --- |
| `cache` | HTTP/model/response caches, `Cache/`, `CachedData/`, `GPUCache/`, `Code Cache/` | safe | Regenerated on next run. |
| `temp` | temp files and temp directories the agent created (`tmp/`, `*.tmp`, `$TMPDIR/<agent>-*`) | safe | Only entries older than 24 h and not open by a running process. |
| `logs` | agent log files that are not tied to a session (`logs/*.log`, rotated logs) | review | Kept for the last `[agents.junk] keep_logs_days` (default 30). Session transcripts are the `sessions` kind below. |
| `build` | build artifacts in agent worktrees and scratch dirs (`target/`, `dist/`, `build/`, `.next/`, `__pycache__/`) | safe | Only under agent-owned worktrees or scratch dirs, never in the user's main checkout; cleared under T152's rules (T342): only when idle (`--idle`, default 24 h), never the cache of the worktree the command runs from unless its path is given, one cache root at a time. |
| `deps` | reinstallable dependencies (`node_modules/`, `.venv/`, `vendor/` with a lockfile, `.gradle/`, Pods) | review | Only in agent-owned worktrees/scratch dirs; requires a lockfile or manifest next to it so it can be reinstalled. |
| `locks` | stale lock files (`*.lock` for agent state, `LOCK`, `.lock` dirs) | safe | Only when no process holds them (checked with the OS); package-manager lockfiles (`Cargo.lock`, `package-lock.json`, `pnpm-lock.yaml`) are never junk. |
| `backups` | backup files (`*.bak`, `*.bak-<ts>`, `*~`, `_backup/` generations past the cap, T249) | review | The newest backup of each file is always kept. |
| `swap` | editor/agent swap files (`*.swp`, `*.swo`, `.#*`, `*.crswap`) | safe | Only when the owning process is gone. |
| `index` | rebuildable indexes (rtok graph/tags index of a removed project, LSP caches like `.rust-analyzer/`, agent codebase indexes, SQLite `-wal`/`-shm` of closed DBs) | review | Rebuilt on next use; the active project's index is never cleared while rtok is running on it. |
| `rtok-own` | today's T182 junk: rotated logs past cap, archives past retention | safe | Existing behaviour, unchanged. |

Paths `clear` may delete (D36, `research.md` §22.2): a path `research.md` §22 documents for that host (official docs or source only), a directory carrying a valid `CACHEDIR.TAG`, or a path the user names in `[agents.junk] extra`. Anything else an agent writes (platform cache roots, Electron subfolders, every path of a host whose §22 cells read "not documented", such as Cursor) is scanned read-only: `list` shows it under the agent with its size and the note "not documented: not cleared (add to `[agents.junk] extra` to clear)", and it never counts toward "Freed by `clear`" or "Freed with `--include review`".

#### Never touched

Settings, credentials and tokens (never read for expiry, never deleted; D36), MCP and hook config, installed plugins and extensions, user-written files (rules, memories, prompts, skills), the `rtok.db` file and its `-wal`/`-shm` files (never deleted, moved or emptied; its rows change only through the store's retention, `Store::run_retention`, which `clear` already runs, and `Store::remove_project`; junk adds no row deletion of its own, T340), any archive a call still references, the user's main checkouts, package-manager lockfiles, and anything outside the paths listed per host. Session history and snapshots are touched only by an explicit `--kind sessions` run (see the `sessions` row). Symlinks are never followed out of an agent folder.

#### Cache: rtok's own and each agent's

Cache is junk for rtok itself and for every agent, listed with its size and clearable like any other kind.

**rtok's cache (row `rtok`):**

- The per-project LSP state rtok confines language servers to (`<root>/.rtok-lsp-xdg/{cache,data,state,pub-cache}`, `src/plugins/graph/lsp.rs`): `cache` and `pub-cache` are `cache` kind (safe); `data` and `state` are `index` kind (review).
- Graph/tags index rows: rows of a gone root are dropped by store retention (`Store::run_retention`) and rows of a removed project by `Store::remove_project`; junk shows nothing extra for them and deletes nothing else (T340).
- Any other directory under rtok's home or `$XDG_CACHE_HOME/rtok` (`~/Library/Caches/rtok` on macOS, `%LOCALAPPDATA%\rtok\cache` on Windows) that rtok writes as a cache, and every directory under rtok-owned paths carrying a valid `CACHEDIR.TAG` (the same test `worktree::list::is_cache_dir` uses).
- Plugin download/staging caches (version-numbered plugin copies a host no longer points to, T279): `cache` kind (safe) once no host config references them.

**Each agent's cache:**

- **Cleared** (D36) only from: (1) the host's entry in `research.md` §22 (documented cache dirs only); (2) any directory under the agent's folders carrying a valid `CACHEDIR.TAG` (deleted under T152's rules (T342): only when idle (`--idle`, default 24 h), never the cache of the worktree the command runs from unless its path is given, one cache root at a time); (3) `[agents.junk] extra` entries with `kind = "cache"`.
- **Listed only** (size shown, "not documented: not cleared", not in any "Freed" total): the platform cache root for that app (`~/Library/Caches/<bundle id or name>`, `$XDG_CACHE_HOME/<app>`, `%LOCALAPPDATA%\<app>\Cache`) and well-known Electron/Chromium cache subfolders inside the app's data dir (`Cache`, `Code Cache`, `GPUCache`, `CachedData`, `DawnCache`) when §22 has no row for them. `Service Worker/CacheStorage` is stored app data, not cache, and is not listed as cache. A heuristic path that gets a cited §22 row moves to "cleared" with no other change.
- Not cache even if the name says so: anything §22 marks as settings or state, extension/plugin install dirs, and model weights the user downloaded on purpose (listed as `never`, size only).
- Environment overrides are honoured (`XDG_CACHE_HOME`, `CLAUDE_CONFIG_DIR`, `CODEX_HOME` and the like listed in §22), so a relocated cache is still found.

**Sizes.** Disk usage per cache directory (allocated blocks), hard links and APFS clones counted once across the whole report, symlinks not followed. Shown per cache folder and summed per agent; the same numbers feed "Freed by `clear`".

**Clearing.** `cache` is `safe`, so `rtok agents junk clear` (dry run) lists every clearable cache folder (D36) and its size in the plan, and `--yes` deletes their contents, keeping the top folder (so apps that expect it to exist don't fail) and keeping any `CACHEDIR.TAG`. `--kind cache` clears only caches; `--agent rtok --kind cache` clears only rtok's. A running agent's cache is skipped unless §22 says that host tolerates it (reason "agent running").

**Cache edge cases:** a cache dir shared between two agents (for example a common Electron runtime cache) is shown under both with a "shared with" note, counted once, and cleared once; a cache dir that is a symlink to another volume is listed by its target with a note and cleared only inside the target; a cache that refills during the clear (app running despite detection) is reported as "freed X, now Y"; a `CACHEDIR.TAG` with the wrong signature does not make a folder cache.

#### More junk kinds

| Kind | What | Class | Detection | Kept |
| --- | --- | --- | --- | --- |
| `sessions` | old sessions and their logs: transcripts (`*.jsonl`), per-session log files, per-session attachments, tool output dirs and per-session snapshots (for example Claude Code's `file-history/<session>/`) | explicit | only on hosts whose `research.md` §22.1 sessions cell documents the whole session unit and the index the host keeps beside it (T330.5 records the per-host verdict); never the host's memory, index or store files (Claude Code `projects/<project>/memory/`, Kimi `session_index.jsonl`, Copilot `session-store.db`, Codex state DB); never rtok's own session and agent rows in `rtok.db` (T284; the logs keyed by session id age out with their calls through store retention, T340) | anything touched within `stale_session_days` (default 30, creator 2026-10-03); never in a default or `--include review` run, only with `--kind sessions`; `list` shows the host's own retention next to rtok's (Claude Code `cleanupPeriodDays`, Gemini `general.sessionRetention`); see "Old sessions: time only" below |
| `stale-worktrees` | temporary worktrees an agent created and did not finish: exactly the ones `rtok worktree gc` would remove (rtok-tagged worktrees, T285, or host-created ones, T289) | review | `rtok worktree gc`'s verdict (`gc::decide`, T153): merged, clean, idle at least `stale_worktree_days` (default 14), lock absent or own, agent not live | everything `gc` keeps, each listed with gc's reason (unmerged or unpushed work, uncommitted changes, a lock owned by another, a live agent, the main or current worktree) |
| `crash-dumps` | dumps and tracebacks from crashed runs: `*.dmp`, `*.crash`, `*.ips` for the agent's binary, Crashpad/Breakpad `Crashpad/completed`, `pending` folders, panic logs, core files named for the agent | safe after 7 days, review before | §22 crash paths; macOS `~/Library/Logs/DiagnosticReports/<app>*`; Linux `$XDG_STATE_HOME`/app crash dirs (not system `/var/crash`, which needs root) | dumps newer than 7 days stay unless named with `--kind crash-dumps` |
| `snapshots` | agent state snapshots not tied to one session: per-project checkpoint/undo stores and shadow git repos (Gemini `~/.gemini/history/<project_hash>`) | never | §22 snapshot paths per host; not scanned for hosts §22 marks "not documented" | always kept: size only, with the host's own restore command named; per-session snapshots go with their session (`sessions` row) |

Every kind in this table follows the same rules as the rest: listed per agent with folder links and sizes, included in "Freed with `--include review`" (or "Freed by `clear`" for safe ones; `explicit` and `never` kinds are in neither), deleted only by `clear --yes`.

Extra edge cases for these kinds:

- `sessions`: a session file still being appended to (modified in the last 10 minutes, or open by a process) is never removed; a session whose project folder is gone is still listed under "(project missing)" with its path.
- `stale-worktrees`: removal is gc's per-record `worktree::remove::detach` (T286), one record at a time (never `rm -rf` on a checkout); never a blanket `git worktree prune`; orphans (main repo missing) and stale records are listed and reported, never removed; the merged local branch goes too, as in `rtok worktree gc`, because merged means its content is in the base (T341).
- `crash-dumps`: dumps can contain memory with secrets; they are never uploaded, copied or printed, only sized and deleted.
- `sessions`: hosts that keep per-session snapshots for "undo" lose undo for cleared sessions; the row says so. `snapshots` are never cleared.

#### Old sessions: time only

- **The only criterion is time.** A session is old (junk) when it has not been touched for longer than the threshold. Nothing else classifies a session as old for now: not its status (finished, errored, abandoned), size, number of messages, project, whether it is the newest session of its project, or whether it was ever resumed. Those criteria are out of scope for T330 and may come later as separate options.
- **Setting:** `stale_session_days` in the `[agents.junk]` table of rtok's config. Default `30` (creator 2026-10-03; it applies only to an explicit `--kind sessions` run, D36). It lives in `config/default.toml` (shipped default), can be overridden in the user config (`~/.config/rtok/config.toml`), per project in `<git root>/.rtok.toml`, and per run through the environment (`RTOK_AGENTS_JUNK_STALE_SESSION_DAYS=7`), with the usual precedence (flags over env over project over user over default). `rtok config get agents.junk.stale_session_days` prints the effective value and `rtok config show --sources` shows where it came from. Documented in `docs/config.md` (and ru/uk).
- **Value rules:** a whole number of days, `0` to `3650`. `0` means every session not currently open counts as old (useful for a full wipe, and `list` warns "threshold 0: every closed session is junk"). Fractions, negatives and non-numbers are rejected by `rtok config validate` and at load with an error naming the key; rtok then falls back to the default for that run and says so, rather than deleting with a wrong threshold.
- **One-off override:** `rtok agents junk list|clear --session-days N` overrides the setting for that run only, and the output header shows the threshold in use ("old sessions: not touched for more than 30 days").
- **"Touched" means** the session's last-used time as defined in the item breakdown: the host's own last-activity timestamp when its format records one, otherwise the newest modification time of any file belonging to the session (transcript, its log, its attachments). Reading a session (for example `list` itself, or `rtok stats` ingesting it) does not count as touching it.
- **Threshold comparison:** a session is old when `now - last_used > stale_session_days * 24 h`, measured in UTC so time zone and daylight-saving changes don't shift it; exactly at the threshold it is not old yet.
- **Safety guards (not classification):** a session that is open by a process or was modified in the last 10 minutes is never removed even with threshold `0`, because deleting a session being written can corrupt the agent's state. `list` shows such a session as old-by-time but "skipped: in use".
- **All parts of an old session go together:** transcript, its per-session log, attachments and tool-output dirs are one item with one size; clearing removes all of them or none.
- **Edge cases:** a session whose files have different mtimes uses the newest one; a session with a future timestamp (clock skew) counts as just touched and is shown with a "future timestamp" note; a host that records no timestamps and whose files rtok cannot stat is listed as "last used unknown" and not cleared; changing the setting takes effect on the next `list`/`clear` with no restart; lowering it from 30 to 7 makes more sessions old in the next `list`, and nothing is deleted until `clear --yes`.
- **Expected results:** with the default, a session last touched 31 days ago is listed as old and removed by `clear --kind sessions --yes` (not by `--include review`); one touched 29 days ago is listed with "under the 30-day threshold" and kept; setting `stale_session_days = 7` and listing again marks the 29-day-old session old; `--session-days 60` on one run keeps both and the header says 60.

#### Item-level breakdown in `list`

`list` shows not just totals but every concrete item that `clear` would delete, so the user can verify the exact set before running `clear --yes`.

- Under each agent and each kind, the items are listed one per line: a link to the item (OSC 8 `file://` hyperlink in a capable terminal, plain path otherwise), its size, and, for anything time-stamped, **last used** (relative and absolute, for example `3 weeks ago (2026-09-09 14:02)`).
- "Last used" means, in order of preference: the host's own last-activity time when its format records one (session `updated_at`, last message timestamp); otherwise the newest modification time of any file inside the item; atime is not used (often disabled). For worktrees: the last commit time or the newest file change, whichever is later, and the owning agent id.
- Each item also shows why it is junk (for example "not touched for 35 days (threshold 30)", "agent session ended 16 days ago", "crash dump 12 days old") and, if it is not going to be cleared, why ("touched 29 days ago, under the 30-day threshold", "uncommitted changes", "agent running", "review kind: add --include review").
- Items within each kind are sorted by size, largest first; `--sort size|last-used|path` (default `size`) changes the order. To keep the default output readable, each kind shows its 10 largest items and a "+N more (X)" line; `--items all` shows every item, `--items 0` shows totals only.
- `--json` always includes every item: `{ path, size_bytes, last_used, reason, will_clear, skip_reason? }`.
- `clear` (dry run) prints the same item list as `list` restricted to what it would delete, so what the user reviewed is exactly what `--yes` removes. `clear --yes` re-scans and refuses to delete an item that changed since the dry run in a way that would change the decision (became recent, gained uncommitted changes, became open by a process) and reports it as skipped.
- `rtok agents junk list --agent claude --kind sessions --items all` is the way to see every session with its last-used time.

Breakdown edge cases: a kind with thousands of tiny items (temp files) is grouped by parent folder in text output ("1,240 files in /…/tmp, 380 MB, last used 2 days ago") while JSON keeps every file; an item whose last-used time cannot be read shows "unknown" and is treated as recent (not cleared) unless `--older-than` was given and the folder mtime satisfies it; times are shown in the local time zone; paths under the home directory are shown with `~`.

#### `rtok agents junk list`

- Scans every agent (or `--agent <host>`, repeatable; a host id, plus the pseudo-host `rtok`) and prints, per agent: the agent name, each agent folder with a clickable link (OSC 8 hyperlink `file://` in a terminal that supports it, the plain path otherwise), the folder's total size, and under it each junk kind with its size and item count, then a line "Freed by `clear`: X" (safe kinds only) and "Freed with `--include review`: Y".
- Sizes are shown in human units with one decimal (B, KB, MB, GB, TB; 1 KB = 1024 B, labelled as such in `--help`) and right-aligned; `--bytes` prints exact bytes. Size means disk usage (allocated blocks), not apparent size, so sparse files and APFS clones are not over-counted; hard links are counted once.
- Ends with a total across agents: folder sizes, junk by kind, space freed by default and with review kinds.
- Sorting: agents are always ordered by space freed, largest first, with no flag; `--sort size|last-used|path` (default `size`) orders the items within each kind. `--kind <kind>` (repeatable) filters kinds. `--min-size 10MB` hides smaller rows.
- `--json` prints the same data: `agents[] { name, id?, folders[] { path, size_bytes, kinds[] { kind, class, size_bytes, items, paths_sample[] } }, freed_default_bytes, freed_review_bytes }`, `totals`.
- Agents that are not installed are skipped; `--all` lists them with "not installed".
- Read-only: `list` never deletes, moves or touches files (no atime updates where the OS allows avoiding them).

#### `rtok agents junk clear`

- Same scan as `list`, then removes. Default is a **dry run**: it prints exactly what would be deleted (per agent, per kind, sizes, the space that would be freed) and changes nothing. `--yes` deletes. `--json` works for both.
- By default only `safe` kinds are cleared. `--include review` adds review kinds; `--kind <kind>` limits to named kinds (a named review kind is included without `--include review`; an `explicit` kind is cleared only when named). `never` kinds and paths without D36 evidence are never deleted.
- `--agent <host>` (a host id, plus the pseudo-host `rtok`; on `rtok worktree` commands the same flag is an agent id, D34) limits to one or more agents; `--older-than 7d` applies an age floor to every kind.
- Before deleting each item, rtok re-checks it (still exists, still matches the kind, not open by a process, not a symlink pointing outside, not modified in the last minute). Anything that fails the check is skipped and reported.
- Deletion goes to the OS trash when `--trash` is given (macOS Trash, freedesktop trash on Linux, Recycle Bin on Windows); otherwise it is a direct delete.
- Output after `--yes`: per agent and kind, items removed, bytes freed, items skipped with reasons, and a total "Freed X of Y planned".
- Exit codes: 0 when everything planned was removed (or on a dry run), 1 when some items could not be removed (as T182 does today: "some junk could not be removed"), 2 on usage errors.
- The widening is deliberate: `rtok agents junk clear` with no flags clears the safe kinds of every agent, not only T182's `rtok-own` junk. `--agent rtok` clears rtok's own safe kinds, a superset of what T182 cleared. T182's tests are updated where they pin the exact set.

#### Running agents

- An agent whose process is running is detected (process list per host binary, plus live rtok sessions from T284). For a running agent, `clear` skips `temp`, `locks`, `swap`, `index` and the current session's logs, and says so; caches are still cleared only if the host's §22 entry says it tolerates that while running, otherwise skipped with "agent running". `build` and `CACHEDIR.TAG` dirs follow T152's idle rule whether or not the agent runs (T342).
- `--force-running` is not offered; the user closes the agent and runs `clear` again.

#### Config

`[agents.junk] stale_session_days = 30`, `keep_logs_days = 30`, `stale_worktree_days = 14` (the idle window passed to `rtok worktree gc`'s policy for junk), `crash_dump_min_age_days = 7`, `temp_min_age_hours = 24`, `exclude = []` (glob paths never touched), `extra = []` (extra paths per host to treat as a kind, for example `{ host = "cursor", kind = "cache", path = "~/Library/Application Support/Cursor/CachedData" }`), documented in `docs/config.md`.

#### Edge cases and expected results

- A folder that does not exist: not listed. A folder without read permission: listed with "permission denied" and no size; `clear` skips it.
- A path on another volume or network share: listed; scanning is bounded (`--timeout`, default 30 s per agent) and a timeout is reported, not hung on.
- Very large trees (a 2 GB `node_modules`): scanning is parallel and streams progress in a TTY; `--json` waits and prints once.
- A file deleted between scan and delete: counted as skipped "already gone", not an error.
- The same folder reached from two agents (shared cache): listed under each with a "shared with" note and counted once in the totals; `clear` removes it once.
- Windows: paths use `%LOCALAPPDATA%`/`%APPDATA%`; files locked by a process are skipped with the reason.
- macOS: `~/Library/Caches` entries are listed as `cache` and cleared only when §22 names them (D36); nothing under `~/Library/Application Support/<app>` is cleared unless §22 lists that subfolder as junk.
- A worktree rtok created for an agent that still has unmerged commits: its `build` and `deps` can be cleared; the worktree itself is never removed (gc's merged rule).
- `--yes` with nothing to clear: prints "Nothing to clear" and exits 0.

#### Also

- `rtok doctor` adds one line with total reclaimable space and a hint to run `rtok agents junk list` when it exceeds 1 GB.
- The web UI gets the same data on a `junk` card on the hosts page (read-only list with sizes and a "clear safe junk" button that runs the dry run, shows it and asks for confirmation), after T310.8; its TUI counterpart is T479 (D27).
- Docs: `docs/agents.md` (new "Junk" section), `docs/config.md`, and `research.md` §22 updated with any new paths, with `docs/ru/` and `docs/uk/` updated in the same change.
- Deliver as a PR; do not merge it.

Dependencies: T182 (existing `clear` and §22 map), T249 (backup generations), T282/T284 (agent ids and live sessions), T285/T153 (agent worktrees).

Check: fixture home under a temp dir, `HOME`/`XDG_*`/`LOCALAPPDATA` pointed at it, no real agent folders touched:

- A fixture with Claude Code, Cursor and Codex folders containing every junk kind: `list` shows each agent, its folders as links, each kind with the exact sizes created (checked in bytes with `--bytes` and in human units), "Freed by `clear`" equal to the sum of safe kinds, and totals equal to the per-agent sums; `--json` matches the table.
- `clear` without `--yes` changes no file (tree hash before equals after) and prints the same plan as `list`'s freed lines.
- `clear --yes` removes exactly the safe items; settings, credentials, plugins, package-manager lockfiles and `rtok.db` are untouched; freed bytes match the plan.
- `--include review` also removes logs older than `keep_logs_days`, `node_modules` with a lockfile, extra backups (newest kept) and rebuildable indexes; `node_modules` without a lockfile is not removed.
- A lock file held by a test process and a swap file of a live process are skipped with reasons; a symlink inside a cache dir pointing to `$HOME/important` is not followed.
- A shared folder is counted once in totals and removed once.
- A simulated running agent skips temp, locks, swap, index and its current session.
- Cache: rtok's `.rtok-lsp-xdg/cache` in a fixture project, a `$XDG_CACHE_HOME/rtok` folder and a `CACHEDIR.TAG` dir appear under `rtok` with exact sizes; a documented host cache (Copilot's `~/Library/Caches/copilot` or `$XDG_CACHE_HOME/copilot`) appears under that agent and in "Freed by `clear`"; an undocumented `~/Library/Caches/<app>` folder and a Cursor Electron `Code Cache` appear under their agent marked "not documented: not cleared" and in no "Freed" total, and become clearable only after an `[agents.junk] extra` entry names them; `clear --kind cache` dry run lists the clearable ones, `--yes` empties them and keeps the top folders and `CACHEDIR.TAG`; a folder with a bad `CACHEDIR.TAG` signature is not treated as cache.
- New kinds: a session last touched 31 days ago on a host whose §22.1 sessions cell is documented is listed with its last-used time, kept by `--include review` and removed with `--kind sessions` together with its per-session snapshots, while the host's memory and index files stay; one touched 29 days ago and one modified 5 minutes ago are kept; a finished-session worktree idle 20 days that `rtok worktree gc` would remove is removed per record by gc's rules and its merged branch deleted, while one with uncommitted changes and one with unpushed commits are listed and never removed, and no blanket `git worktree prune` runs and an orphan is only reported; token and credential files are never read for expiry and never touched, even with `--kind`; a 10-day-old crash dump is cleared by default, a 2-day-old one only with `--kind crash-dumps`; a per-project snapshot store (Gemini shadow git) is listed with its size and never removed.
- Session threshold: `rtok config get agents.junk.stale_session_days` prints 30 by default; `.rtok.toml` and `RTOK_AGENTS_JUNK_STALE_SESSION_DAYS` override it; an invalid value (`-1`, `2.5`, `abc`) is rejected with an error naming the key and the run uses 30; a session exactly 720 h old is not old, at 720 h and 1 min it is; status, size and "newest in project" do not change the result; an open session with threshold 0 is skipped as in use.
- Breakdown: every planned item appears in `list` with path link, size, last used and reason; `--items all` and `--json` list every item; the default shows 10 per kind plus "+N more"; `clear` dry run prints the same items; touching an item between the dry run and `--yes` makes `--yes` skip it with "changed since plan".
- `rtok agents junk clear --agent rtok --yes` clears rtok's own safe kinds, a superset of T182's set; T182's tests are updated where they pin the exact set, the rest stay green.
- Permission-denied and timeout folders are reported, not fatal; exit code 1 when anything planned was not removed.
- `--trash` moves items to the platform trash (tested on macOS and Linux CI).
- `just check`.

Status: closed 2026-10-10 · every subtask (T330.1 to T330.7) shipped; the `rtok tui` counterpart of the web button is T479.
