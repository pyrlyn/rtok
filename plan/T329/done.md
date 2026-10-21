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

Check: every subtask of this epic is in this file, T329.1 to T329.36, T329.40 to T329.43, T329.48 and T329.50 to T329.54 (the other numbers in between were never opened), and `plan.md` and `todo.md` have no T329.x row left.

Status: done 2026-10-12
Model: Claude Code / sonnet-5.5
