Ivan, 2026-10-01: `rtok doctor` finds hooks in agent configs that point to files that no longer exist (broken hooks), hooks that are loaded twice for the same agent (duplicate hooks), and MCP servers that are loaded twice (duplicate MCP entries). It lists them, explains why each is a problem, and lets the user choose to clean them up: broken hooks are removed, and for duplicates one copy is kept and the extra ones removed. Nothing else in any config changes. Covered by many tests.

Today `rtok doctor` (flags `--instructions`, `--json`) reports rtok's own install state and per-host status, and `rtok agents info` reports MCP per surface (T278), including `stale` rtok entries. Install/update rewrites stale **rtok** hook entries in place (T242.x). Nothing checks hooks or MCP entries that are not rtok's, nothing detects a hook whose script was deleted, and nothing detects the same hook or MCP server loaded from two config files (T271 covers one specific case: rtok's MCP seen twice in the Claude desktop Code tab).

#### Terms

- **Agent:** a host in `agents::HOSTS`, per surface where the host has several (for example Claude Code CLI, Claude desktop Code tab, Cursor, Codex, Gemini CLI, Kimi).
- **Config source:** every file that agent reads hooks or MCP servers from, with its scope and load order, taken from `research.md` (per-host config map) and the existing host adapters: user/global files (for example `~/.claude/settings.json`, `~/.claude.json`, `~/.cursor/mcp.json`, `~/.codex/config.toml`, `~/.gemini/settings.json`), project files (`<repo>/.claude/settings.json`, `<repo>/.claude/settings.local.json`, `<repo>/.mcp.json`, `<repo>/.cursor/mcp.json`, `<repo>/.gemini/settings.json`), enterprise/managed files where the host has them (read-only, see below), and installed plugins that contribute hooks or MCP servers (for example Claude Code plugins' `hooks/hooks.json` and `.mcp.json`).
- **Effective set:** what the agent would actually load at the same time for the current directory: every source that applies to that directory, merged the way the host merges them (concatenated hook lists, MCP maps merged by name with the host's precedence).
- **Hook entry:** one hook command: event (`PreToolUse`, `SessionStart`, ...), matcher (if any), command string (or script path), and the source file and JSON/TOML path it lives at.
- **MCP entry:** one MCP server definition: name, transport (`stdio` command + args + env, or `url` for http/sse), and its source file and path.

#### 1. Broken hooks: hooks that lead nowhere

- **Detection:** for each hook entry in each source of each agent, rtok resolves what the command would run, without running it:
  - Parse the command the way the host's shell would split it (POSIX shell words on macOS/Linux, `cmd`/PowerShell rules on Windows where the host uses them). Expand `~`, `$HOME`, `${VAR}` and the host's own variables (`$CLAUDE_PROJECT_DIR`, `${CLAUDE_PLUGIN_ROOT}`, `$CURSOR_...` per `research.md`), using the project directory the check runs for.
  - The **target** is: the first word if it is a path (absolute, `./`, `../`, `~/` or contains a `/`); or the script argument when the first word is a known interpreter (`bash`, `sh`, `zsh`, `node`, `python`, `python3`, `deno`, `bun`, `ruby`, `pwsh`, `npx tsx`, `uv run`); or the program name looked up on `PATH` otherwise.
  - The hook is **broken** when its target does not exist: the path is missing, a dangling symlink, or the program is not on `PATH` (and not a shell builtin).
  - The hook is **suspect, not broken** when the target exists but is not executable while invoked directly (shown with the fix `chmod +x`, not offered for deletion), or when the command cannot be resolved statically (contains `$(…)`, backticks, `eval`, pipes into an interpreter, or an unknown variable): listed as "cannot verify" and never deleted.
- **Report:** a "Broken hooks" section in `rtok doctor` per agent: source file (link), event and matcher, the command as written, the resolved target, and why it leads nowhere ("file not found: ~/scripts/old-guard.sh", "dangling symlink to /Volumes/X/hook.js", "`foo` not on PATH"). It says "can be cleaned up" for each one.
- **Fix:** only hooks classified **broken** can be removed, and only those the user selects (see 4). Removing a hook removes just that entry from its source file: if its matcher group becomes empty, the empty group is removed too; if the event's list becomes empty, the event key is removed; nothing else in the file changes (comments, ordering and formatting kept, through the existing JSONC/TOML editors). The script file itself is never touched (it does not exist anyway).

#### 2. Duplicate hooks

- **What is a duplicate:** two or more hook entries in the same agent's effective set (so loaded at the same time) for the same event, with the same matcher (normalized: same regex or tool list, order-insensitive for lists) and the same command after normalization (variables expanded, paths canonicalized, redundant whitespace and quoting removed, `bash script.sh` equals `./script.sh` only when the script has a bash shebang, otherwise not). Timeouts or other options that differ are shown; entries that differ only in timeout are still duplicates and the one kept is chosen as below.
- **Where duplicates come from:** the same hook in user and project settings; `settings.json` and `settings.local.json`; a hook installed by a plugin and also pasted into settings by hand (or by an older rtok install, T242/T275); a hook repeated inside one file.
- **Not duplicates:** the same command on different events or different matchers; entries in sources that are never loaded together (two different projects, or a disabled plugin); a host that documents running each hook once per unique command (if `research.md` says so for that host, duplicates are reported as "harmless on <host>" with no fix offered).
- **Report:** a "Duplicate hooks" section per agent: the hook (event, matcher, command), how many times it would run, and each copy with its source file (link) and position. It recommends which copy to keep.
- **Which copy is kept (default, user can choose another):** (1) the copy owned by a plugin (so plugin updates keep working); otherwise (2) the copy in the most specific shared scope that is under version control (project `settings.json` over user settings, so teammates keep it); otherwise (3) the user-level copy over `settings.local.json`; otherwise (4) the first in load order. rtok's own hooks follow rtok's install rules (T275): the plugin copy is kept when the plugin serves the hook.
- **Fix:** deletes only the extra copies the user selects, never the last copy. Same minimal-edit rules as for broken hooks. Managed/enterprise files are never edited; a duplicate whose extra copy lives only in a managed file keeps the managed copy and offers to remove the user/project copy instead.

#### 3. Duplicate MCP entries

- **What is a duplicate:** two or more MCP entries in the same agent's effective set that would start the same server at the same time:
  - **same server, same name** from two sources that the host loads together (for example `~/.claude.json` and `<repo>/.mcp.json`), when the host does not de-duplicate by name (per `research.md`; where it does, the overridden one is reported as "shadowed, unused" instead, still removable);
  - **same server, different names**: identical normalized launch (same command resolved to the same binary, same args, same relevant env keys; or the same URL with the same transport), for example `rtok` and `rtok-mcp` both running `rtok mcp`, or a plugin's MCP server also added by hand (the T271 case generalised to every host and every server).
- **Normalization:** command resolved through `PATH` and symlinks (so `/opt/homebrew/bin/rtok` equals `rtok` when that is what `PATH` gives), `npx -y pkg@x` treated as the package `pkg` (versions differing are shown as a conflict, not a duplicate), URLs compared after lowercasing scheme/host and removing a trailing slash; env values are not printed (they may hold secrets), only whether they match.
- **Not duplicates:** same command with different args that change behaviour (for example different `--root` or profile), same name in two projects that are never loaded together, a server disabled in the host's own disable list.
- **Report:** a "Duplicate MCP servers" section per agent: server (name(s), command or URL), how many processes would start, each copy with its source file (link) and key path. Explains the cost (two processes, duplicate tools in the agent's context, double measurement for rtok per D21).
- **Which copy is kept:** same order as hooks: plugin-provided first, then project-shared, then user, then local; rtok's own server follows D33 (T332): the config entry `rtok` that install writes is always kept and never fixable; a same-name copy (merged by the host) or a plugin copy (shadowed, or deduplicated by endpoint) is reported as information only; only a hand-written copy under another name that also runs rtok (for example `rtok-mcp`) is removable.
- **Fix:** removes the selected extra entries only (the key under `mcpServers`, or the `[mcp_servers.<name>]` table in TOML), keeping the file otherwise unchanged; never removes the last copy; never edits managed files or a plugin's own files (a duplicate where the extra copy is inside a plugin is solved by removing the hand-written copy, or by telling the user to disable the plugin, never by editing the plugin).

#### 4. How the user cleans up in doctor

- `rtok doctor` stays read-only by default: it reports the three sections and ends with "N problems can be fixed: run `rtok doctor --fix`".
- `rtok doctor --fix` in a terminal shows a checklist of every fixable item (broken hooks, extra duplicate copies), all pre-selected except items in project files under version control (pre-unselected, since the change affects teammates), lets the user toggle items and change which duplicate copy is kept, shows the exact diff per file, and asks for confirmation before writing.
- Non-interactive: `rtok doctor --fix --yes` applies the default selection; `--fix --only broken-hooks|duplicate-hooks|duplicate-mcp` (repeatable) limits it; `--fix --dry-run` prints the diffs and writes nothing; `--json` works with all of them and lists `problems[] { kind, agent, source, path, detail, fixable, selected, keep? }`.
- Every edited file is backed up first with the existing bounded `_backup` generations (T249), and the backup path is printed. If a file changed on disk between the scan and the write (mtime or hash differs), that file is skipped with "changed since check, run doctor again".
- After writing, doctor re-runs the checks on the edited files and reports the result ("2 broken hooks removed, 1 duplicate MCP entry removed, 0 problems left"). If a write fails (permissions, read-only file), that file is reported and the rest proceed; exit code 1 if anything selected was not fixed.
- The web UI doctor page (T310.7) shows the same sections and a "Fix selected" action with the same confirmation, after T310.7 lands.

#### Edge cases and expected results

- A hook target on an unmounted volume is broken now; the report says "path is on /Volumes/X, which is not mounted" so the user can decide; it is still selectable.
- A hook using `${CLAUDE_PLUGIN_ROOT}` is resolved against that plugin's install dir; if the plugin is uninstalled but its hook entry remains in settings, the hook is broken.
- A hook whose program is a shell builtin or an alias defined only in the user's interactive shell: builtins are fine; aliases are not visible to hooks, so a hook relying on one is reported broken with that explanation.
- Relative script paths are resolved against the directory the host uses for hooks (project root for project settings, per `research.md`), not the doctor's current directory.
- A JSON/TOML file that does not parse: doctor reports "cannot read <file>: <parse error>" and offers no fix for it.
- Windows: `.cmd`/`.ps1`/`.exe` targets resolved with `PATHEXT`; case-insensitive paths.
- Two duplicates plus one broken copy of the same hook: the broken one is listed as broken; the remaining two as duplicates; fixing both leaves one working copy.
- Managed/enterprise policy files are read for the effective set but never edited; problems only fixable there are listed as "managed by your organization".
- A host doctor cannot locate (not installed) is skipped; `--agent <host>` limits the check to one host.
- Nothing to fix: the sections say "none found" and `--fix` exits 0 with "nothing to fix".

Docs: `docs/agents.md` (doctor section) and the doctor help text, with `docs/ru/` and `docs/uk/` updated in the same change. Deliver as a PR; do not merge it.

Dependencies: host adapters and config maps (`research.md`), JSONC/TOML editors, `_backup` generations (T249), T275/T278 (rtok MCP rules and per-surface state); relates to T271.

#### Tests: heavily mocked, many, and isolated from the real machine

**Rule: no test reads or writes a real file, a real agent directory or a real agent.** Every test runs against mocked data only. Concretely:

- **No real paths.** Tests never touch `~`, `~/.claude*`, `~/.cursor`, `~/.codex`, `~/.gemini`, `~/.kimi`, the real `PATH`, real plugin directories, or the repository's own `.claude/`/`.mcp.json`. The doctor code reaches the filesystem, environment and process lookup only through injected traits (`Fs`, `Env`, `Which`, `Clock`), so tests pass mocks and production passes the real implementations. A guard test fails the suite if any doctor module calls `std::fs`, `std::env` or `which` directly instead of the traits.
- **No real agents.** No host binary is launched, no MCP server is started, no hook is executed. Hosts are described by mocked host profiles (which config sources exist, their scope, load order, merge rules, whether the host de-duplicates MCP by name or hooks by command).
- **A belt on top:** integration tests that need real files on disk (golden-file edits, permissions, mtime races) use a fresh `tempfile::TempDir` as a fake `HOME` and fake project, with `HOME`, `USERPROFILE`, `XDG_*`, `APPDATA` and `PATH` overridden for the process, and assert at the end that nothing outside the temp dir was created or changed.

**What is mocked:**

- **File system** (`MockFs`, in memory): files with contents, permissions (executable bit, read-only), mtimes and hashes; directories; symlinks, including dangling ones and symlink loops; unmounted volumes (a path prefix that reports "not mounted"); case-insensitive mode for Windows cases; injected failures (permission denied on read or write, disk full, file changed between read and write).
- **Environment and PATH** (`MockEnv`, `MockWhich`): `HOME`, host variables (`CLAUDE_PROJECT_DIR`, `CLAUDE_PLUGIN_ROOT`, ...), a fake `PATH` with chosen programs present or absent, `PATHEXT` for Windows, shell builtins list.
- **Agent configs:** mocked settings and MCP files per host and scope (user, project, local, managed/enterprise, plugin), in JSON, JSONC (with comments and trailing commas) and TOML, including deliberately unparsable files.
- **Hook definitions:** built with a small builder (`hook(event).matcher(..).command(..).timeout(..)`) and rendered into each host's real config shape, so the same logical hook can be placed in several sources.
- **MCP server entries:** builder (`mcp(name).command(..).args(..).env(..)` / `.url(..)`) rendered into `mcpServers` JSON or `[mcp_servers.<name>]` TOML.
- **Plugins:** mocked installed and disabled plugins with their own `hooks.json` and `.mcp.json`.
- **Doctor output:** the result model (`problems[]`) is asserted structurally; the human table and `--json` output are checked with snapshot tests (`insta`) over the mocked world, with temp paths normalized.
- **User interaction:** the `--fix` checklist reads from an injected `Prompt` trait; tests script the answers (toggle item 2, keep copy B, confirm, or cancel). One pseudo-terminal test drives the real terminal UI against a mocked world.

**How fixtures are structured:**

- `tests/fixtures/doctor/<scenario>/` holds a declarative `world.toml`: host profiles, files (path, contents or a pointer to a file beside it, mode, symlink target), env, PATH programs, plugins; plus `expected.json` (the problems doctor must report) and, for fix scenarios, `after/` golden files and `selection.toml` (what the user picks).
- A `World` builder loads a scenario into `MockFs`/`MockEnv` (unit level) or materializes it in a temp dir (integration level), so the same scenario runs at both levels.
- Shared building blocks: one "clean" baseline world per host with valid hooks and MCP entries; scenarios add or remove a small delta so each test reads as "baseline plus this problem".
- Property tests (`proptest`) generate random hook and MCP sets across sources and check invariants: fix never removes the last copy of anything, never removes a valid non-duplicate entry, and running doctor after fix reports no fixable problems.

**Scenarios that must be covered (each with its expected result):**

- **Broken hook references:** missing script; deleted script that a symlink still points to; program not on PATH; interpreter + missing script for each listed interpreter; `${CLAUDE_PLUGIN_ROOT}` of an uninstalled plugin; relative path resolved against the host's hook directory, not the test's working directory; unmounted volume; quoted paths with spaces; Windows `PATHEXT` and `cmd /c`. Expected: each listed as broken with the right reason and marked fixable.
- **Hooks that must not be called broken:** valid script; builtin; program on PATH; existing but non-executable script (suspect, `chmod +x` hint, not fixable); command with `$(…)`, backticks, pipes or unknown variables (cannot verify, not fixable). Expected: not in the broken list, never offered for deletion.
- **Duplicate hooks across configs:** user + project; `settings.json` + `settings.local.json`; plugin + hand-written copy; repeat inside one file; three copies; copies differing only in whitespace, quoting, matcher-list order or timeout. Expected: one group per duplicate with every copy, the recommended keep matching the keep rules.
- **Not duplicates:** different event; different matcher; sources never loaded together (another project, disabled plugin); host that de-duplicates by command. Expected: no duplicate reported (or "harmless on <host>").
- **Duplicate MCP entries:** same name in two loaded sources; different names with the same launch; plugin MCP plus hand-written entry (the T271 case on every host); URL servers differing only by case or trailing slash. Expected: one group per server, process count, keep recommendation; env values never appear in output.
- **MCP that must not be called duplicate:** same command with behaviour-changing args; `npx pkg@1` vs `pkg@2` (conflict instead); disabled server; host that resolves by name (reported "shadowed").
- **User selecting cleanup:** default selection with `--yes`; toggling items off; choosing a different copy to keep; `--only` per kind; `--dry-run`; cancel at the confirmation. Expected: only the selected entries disappear, golden files match byte for byte (comments, ordering and formatting kept), empty groups and events removed, backups created, cancel and dry run change nothing.
- **Refusing to delete valid hooks and entries:** a selection file or JSON input that names a valid hook, a suspect hook, a "cannot verify" hook, the last copy of a duplicate, an entry in a managed file or in a plugin's own files. Expected: doctor refuses each with a clear message, writes nothing for it, and exits with code 1 if it was explicitly requested.
- **Failure and race cases:** unparsable config (reported, untouched); read-only file (reported, others fixed, exit 1); file changed between scan and write (skipped with "changed since check"); write failure midway (the file is left as it was, the backup is intact).
- **Combined case:** a world with broken hooks, duplicate hooks and duplicate MCP entries across several hosts at once; after `--fix --yes`, re-running doctor reports zero fixable problems and every valid entry is still present.

Check: all of the above pass on Linux, macOS and Windows CI; the "no real paths" guard test passes; `just check`.

Split (epic, too large for one PR: three detectors, a fix engine, an interactive UI, ~15 host config shapes; each part ships read-only value or a tested edit and is one PR). Order: T331.1 broken hooks (read-only, the injected `Fs`/`Env`/`Which` seam every later part reuses), T331.2 JSON hook files of the other hosts and of Claude plugins, T331.8 TOML hook files, T331.9 `--agent` and Windows rules, T331.3 duplicate hooks, T331.4 duplicate MCP entries, T331.5 `--fix` for broken hooks (the edit/backup/race engine), T331.6 `--fix` for duplicates, T331.7 interactive checklist, docs and the property/pty tests, T331.12 the web doctor action. T331.10 (rtok's own MCP entry in the duplicate check) and the rtok part of T331.6 follow the T332 decision (D33 wins, see T331.10); nothing waits on an open decision any more.

Closed 2026-10-10 with T331.10, the last of T331.1–T331.12 (all in `done.md`). Status: done 2026-10-10 · Model: Claude Code / claude-opus-5-5
