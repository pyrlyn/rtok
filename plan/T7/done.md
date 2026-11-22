# T7. Done

Whole-repo review of the Kotlin port for bugs: logic errors, races, resource leaks, bad error handling,
load and edge-input failures. Twelve review units covered every module; each finding was re-read against
the code before it was fixed, and each fix is one `T7:` commit on `t7-review-fixes`.

K1–K5 were fixed earlier (#278). The task was then taken over with a narrower brief from the creator:
bugs first. Dead code goes to T9 and architecture changes to T10, so the only optimizations here are
the ones that remove a quadratic scan or an unbounded buffer found during the bug hunt.

## Fixed

- **rtok-sys, rtok-store**: the walk never follows symlinks and skips unreadable directories; file
  helpers report failed and interrupted I/O; lock files open close-on-exec; pipes already open are
  closed when a later `pipe()` fails; SIGPIPE no longer kills rtok in `spawnDetached`; the clone
  fallback copies only regular files; `moveByCopy` refuses an existing destination; the Linux trash
  name is reserved with an exclusive create; the Linux watcher takes only real directories; the zstd
  cap is clamped to the largest array; the archive sequence counter is atomic.
- **Config and libs**: a broken config keeps the caller's flags; StrictJson stops at depth 128;
  `not-any-of?` is a negated `any-of?`; the locals query is measured in bytes; tree-sitter matches are
  collected eagerly; a streamed response is released when its headers cannot be sent; the HTTP servers
  ignore SIGPIPE; a TOML MCP entry is replaced together with its subtables.
- **CLI, doctor, otel, web**: `guard check` fails open on a bad config; help reached through an error
  exits 2; `doctor --fix --yes --stat` no longer writes; negative `--days` and `--budget` are
  rejected; doctor leaves an interpreter or `uv` hook it cannot resolve unverified; `rtok filter`
  passes `--exit` on without `--archive`.
- **Graph**: a stale-fingerprint index no longer wipes its rows before the new ones exist; watch jobs,
  LSP sessions, dead-code and export paths close what they open and survive a failing file.
- **MCP, proxy, demon, hooks**: the frame buffer compacts and is capped; the round trip decodes whole
  lines; the wrapper TERMs then KILLs a server that outlives its stdout by 2 s; the resident socket has
  a receive timeout; the demon claims its state patiently and pauses in short steps so a stop is seen.
- **Tasks, worktrees**: task ids come from the shared store counter, seeded from the board, so two
  boards never hand out one id; `worktree add` creates its directory exclusively; migration and finish
  paths stop on a failed git call instead of going on.
- **Agents, agent SDK**: backups reserve their slot with an exclusive create; `writeAtomic` writes
  through a symlink, uses a unique temp name and removes it on failure; a versioned plugin copy is
  staged and renamed in; `PluginLink` asks before it replaces a stale copy and a dry run reports
  "no changes" for a current one; hook ownership needs `rtok` as a whole word; the OpenCode proxy
  removal keeps a user's own URL.
- **Plugins**: `cat -n` has its own read-cache mode; grep output of a single file is not grouped;
  the filter fails open; the diff table is capped; the grammar cache is safe across threads; the guard
  honours backslash escapes and treats `MultiEdit`, `NotebookEdit` and `apply_patch` as mutators;
  memory titles are one line; an observation scrubs a bounded head; inject mode names cannot leave
  `modes_dir`; `llms` docs always read this workspace's version; a WASM guest that fails in any way is
  skipped, and its log text is stripped of control characters.
- **TUI, measure**: the TUI ends on SIGTERM, SIGHUP and a closed terminal, retries interrupted writes
  and redraws in full after a short one; C1 controls have no width; the stats page closes its stores;
  Codex input never goes negative; a PNG with a zero dimension is not an image; the deny follow-up
  window stays inside the list.

## Behaviour changes to know about

- Help printed because of a usage error exits 2, not 0.
- `rtok guard check` and the archive rewrite fail open on a broken config.
- `doctor --fix --yes --stat` only reports.
- The walk never follows symlinks; `moveByCopy` refuses an existing destination.
- The MCP wrapper ends a server 2 s after its stdout closes.
- Task ids are taken from the store counter; `PluginLink` asks before replacing a stale copy.
- The guard clears the read cache after `MultiEdit`, `NotebookEdit` and `apply_patch` too.

## Not fixed: needs the creator's decision

- **Security**: `rtok doctor` starts every MCP server named in the current directory's `.mcp.json`,
  so running it inside a cloned repository runs that repository's commands. Fixing it changes what
  doctor checks.
- A process-wide `SIGPIPE` ignore in `main`: it would change how rtok ends when its own stdout pipe
  closes.
- `rtok bench check` has no timeout for a hung command.
- `plugins.inject.modes` and `plugins.inject.budget_tokens` are not in the project-restricted key list.
- The MCP edit tools have no allowlist of their own; Rust parity questions in the guard.

## Not fixed: smaller, left for later

KtorHttpServer reads a body under the server lock; `Git.withLockHeld`; `processStartTime` precision in
the demon; zombie reaping in `spawnDetached`; a Myers diff for the read cache; a length cap in
`ReadFs`; read-then-write in Messages and Observations. The three web modules, the TUI key and row
files, the model pages, the PDF report sections and the bench suite were not reviewed in depth.

## Checks

- `./gradlew macosArm64Test cliTest e2eTest`: passes on the branch head (macOS arm64).
- `./gradlew -Prtok.wasm=true :rtok-wasm:macosArm64Test`: passes.
- `mise run size-check`: 74,359,592 B of the 79,000,000 B budget.
- ktlint and detekt run inside `macosArm64Test`. The detekt baselines were regenerated: ids of renamed
  or split functions follow their new names, and the test classes that grew past the limits are listed.

Linux-only sources (`linuxMain`, the `#else` branches of the C shims) were edited but cannot be compiled
on the macOS host; CI on the PR is their first build.
