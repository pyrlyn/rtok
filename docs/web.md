# Web UI and dashboard port (`rtok web`, `rtok dashboard`, `rtok tui`)

Port of `src/web` (axum server + WebSocket protocol), the React SPA in `web/` and `src/tui` (ratatui).
Everything is rewritten in Kotlin, the frontend included (Ivan, 2026-10-27): no prebuilt React bundle is embedded.
Previously excluded from the port (port-plan.md); now in progress in small steps.

## Plan
1. (done, #103) `:rtok-web` core: `WebToken` (src/web/token.rs, S2 token file, 0600, constant-time compare,
   Bearer/`?token=`) and `checkRemoteBind` (`RTOK_WEB_ALLOW_REMOTE`).
2. (done, #105) `/health`, `/auth`, SPA static serving over `:http-server` (Ktor CIO), `rtok web --host --port` CLI.
3. (done, #107) WebSocket on `:http-server` (`ktor-server-websockets`, same Ktor version), origin check, snapshot ticks
   with build coalescing (T206), `protocol.rs` frames via kotlinx.serialization.
4. (in progress) Operator model (`src/model`, D23) shared by web and TUI: snapshot built from `:rtok-store`
   (calls, usage, sessions, projects, tasks, worktrees), config, agents, doctor, junk.
5. Inbound actions: `set`, `expand`, `project`, `doctor`, `junk`, `graph`, `diff`, `export`, `import`, `calls`;
   live graph calls (`live.rs`), calls store/view.
6. (in progress, #128 #130 #132) Frontend: a Kotlin/JS module `:rtok-web-ui` (kotlinx.html for the DOM, kotlin-css for styles), speaking the
   same `/ws` protocol and matching the Rust SPA's pages and features. Charts are drawn by our own small SVG
   helpers in Kotlin (no JS chart library); the graph view uses our own Canvas/SVG force layout. The built
   bundle is embedded in the binary as an `Assets` source; `RTOK_WEB_DIST` still overrides it for development.
7. `rtok tui` / `rtok dashboard`: the same model rendered in a terminal. Own small `:tui` module over
   termios + ANSI (no stable Kotlin/Native ratatui equivalent; Mosaic is Compose-based and heavy).

## Status
- Step 2: `rtok web` serves `/health`, `/auth` and the UI files from `RTOK_WEB_DIST` (SPA routing, br/gzip
  variants, ETag, CSP). Since step 6 the binary embeds the Kotlin/JS UI (`:rtok-web-ui`, `EmbeddedAssets`); a build without it answers page routes with 503 and a hint.
- Step 3: `/ws` is admitted under the same token and origin gate as `/auth`, then sends a snapshot at once, every
  2 s and after each inbound frame; concurrent ticks share one build (T206). Until step 4 the snapshot is
  `{"type":"snapshot"}`.
- Step 4, slice 1: `rtok.model.Snapshot` sends `calls`, `ref_ids`, `sessions` and `logs`; the other pages go out
  as their "not yet" values (`doctor_pending: true`, `hosts: "probing hosts…"`, nulls) until their slices land.
- Step 4, slice 2: `usage` (Overview: totals, CTT, turns, plain-proxy alerts, 14-day savings trend).
- Step 4, slice 3: `plugins` (catalogue pages, memory/checkpoint live fields, config fields, grouped stats).
- Step 4, slice 4: `graph` (status + capped dead list, `[project]` tags over a linked scope) and `projects`.
- Step 4, slice 5: `config` (`config show --sources` rows, secrets redacted, never writes the user file).
- Step 4, slice 6: `hosts` (`agents list` blocks through a `Background` worker cache, 30 s TTL; the junk
  section reads "measuring folders…" until the junk port lands).
- Step 4, slice 7: `agent_usage` (`agents usage` text and JSON rows, read off the tick, 120 s TTL).
- Step 4, slice 8: `worktrees` (`worktree list` table, 300 s `WORKTREES_TTL` worker cache) and `junk` (one cached
  `agents junk` report: the Hosts page's junk section text and the `JunkCard` JSON, same TTL).
  Still to come: `tasks`, `skills`, `stats`, `doctor`, waiting for their ports.
- Step 5, slice 1: `rtok.web.Answer` replies to `expand` and `set` (allowlisted `plugins.<id>.enabled`, config
  reloaded for the next snapshot), tags replies with `req` (T623), and answers malformed requests with Rust's notices.
- Step 5, slice 2: `rtok.web.GraphAnswer` answers the graph page: `project` (select, link/unlink, reindex on the
  `Jobs` worker, remove as a `project_plan` dry run until `confirmed`), `graph` (drill-down), `diff` (Compare mode),
  `export` (JSON/SVG; PNG still errors, see port-plan.md) and `import` (parse only). `doctor` and `junk` wait for
  their web ports.
- Step 5, slice 3: `rtok.web.CallsStore` (calls_store.rs: running/feed, interrupt sweep, windowed totals,
  sparklines, scope and folded counters, feed filters), `CallsView` (calls_view.rs, the `calls` frame) and
  `coalesce`/`CallBatch` from live.rs. Pure logic; the poller, `Reader` and the `calls` subscription come next.
- Step 5, slice 4: live calls on `/ws` (live.rs): `Reader` (cursor at the newest event, replay since the server
  started, caller names from agents/sessions), `LiveCalls` (one poller while a socket listens, a `StateFlow` of
  `calls` frames, published only on change) and `{"calls":{"subscribe":bool}}` handled by the socket loop.
- Step 6, part 1 (#128): `:rtok-web-ui` scaffold. No npm bundler: the JS production executable is one
  whole-program file; `webDist` writes index.html, theme-init.js and `assets/app-<hash>.js`, and `:rtok-web`
  embeds that directory (`embedDir` in buildSrc) as `EmbeddedAssets` with content-hash ETags. Shell, `/ws` link
  (token, backoff, Offline screen), theme toggle. A page not ported yet shows its snapshot field as JSON.
- Step 6, part 2 (#130): Overview (format.ts, model.ts `overview`) and our own SVG charts.
- Step 6, part 3 (#132): Calls, Sessions and Logs with filters kept in the URL query.
- Step 6, part 4: Config and Hosts (text.ts `parseConfig`/`parseHosts`, raw-text switch in `?raw=`).
- Step 6, part 5: Services (text.ts `parseServices`, otel exporter tiles).
- Step 6, part 6 (#137-#143): Plugins (switch sends `set`), Usage, Graph (projects, alerts, status, dead
  symbols), the live graph-calls feed (`{"calls":{"subscribe":...}}` while the Graph page is open) and the
  command palette with `g <key>` shortcuts. Doctor, Stats, Skills, Worktrees, Tasks and the Hosts junk card
  show "not ready" while the server sends null for their field.
- Step 6, part 2: the Worktrees page (Worktrees.tsx: state filter, search, totals).
- Step 6, part 3: the Hosts junk card (Junk.tsx, junkState.ts) and `{"junk":{"action":"plan"|"apply"}}` on `/ws`
  (`rtok.agents.JunkWeb`, junk_web.rs): `plan` is the dry run of `agents junk clear` for every agent, `apply`
  re-plans and removes only the confirmed paths, then drops the cached card.
- Step 6, next: full Doctor (plan/fix), Stats, Skills and Tasks once their backends
  reach the snapshot; Graph 2D force layout, drill, compare, export/import. Pure logic goes to commonMain so
  the native gate tests it.
- Step 7, part 1: `libs/tui` (`:tui`), no dependencies: styles/SGR, display width, a cell buffer with
  diff rendering, layout, widgets (block, paragraph, tabs, table, sparkline), key decoding, and a
  termios/ANSI `Terminal` (raw mode, alternate screen, size, poll).
- Step 7, part 2: `rtok tui [--tab] [--tick-secs]`: tabs from model::pages(), re-read of the same Snapshot
  every tick and on `r`, tables for plugins/calls/sessions, logs and the CLI text pages, overview totals with a
  ctx sparkline, `?` help, the tty guard. Part 3: Plugins Space/Enter toggle through `Validate.set`
  (app.rs T15.4, status in the key line) and the Hosts `c`/`y` clear-safe-junk panel over `JunkWeb` (junk_clear.rs
  T479), both through `rtok.tui.TuiActions`. Part 4: Sessions `l` live-only filter (T60.10), Config `/` filter
  (T228) and the Calls `e` expand pane with its own `/` filter (T60.4), an open filter owning every key (T637).
  Part 5: the Calls Enter/z detail pane (view.rs `call_detail`, T15.5) and the Sessions Enter pane
  (`session_pane` over model::session_detail, T60.3). Part 6: the Graph tab's registry (projects.rs T476/T477:
  select, link/unlink with a target picker, re-index on the `Jobs` worker, remove after its dry run; all through
  `GraphAnswer.projectWrite`, the page's own write), the health cells and breakdown (health.rs T481) and `d` drill
  (drill.rs T561, loaded on the key until the loop has a worker). Part 7: the panel shell the Graph tab's panels
  share (panel.rs T329.48) and `c` compare (compare.rs T485) over `GraphDiffs.pageOf`, a typed ref list or a saved
  export path. Part 8: `e` export form and `v` saved-export view (exporter.rs T329.41) over `ExportView.write`/`read`;
  png is refused as `graph export` refuses it in this build. Panel text wraps to the width (`tui.wrap`). Part 9: the live
  calls pane (live_calls.rs T480) under the Graph tab: the web's `Reader` polled from the loop every `POLL_MS` while
  the tab is up, folded by the web's `CallsStore` (`copy()` holds a freeze), `f`/`w`/`a`/`t`/`o`, the scope mark and
  the drill's folded counters. Part 10: model reads and the Graph drill run on `TuiWorker` (app.rs `Worker`: one thread, queued requests collapse to the newest, inline when the thread will not start), the key line shows `◌ loading` mid-read. Part 11: the one key table (app.rs `KEYS`, T60.8) the footer and the per-page `?` overlay (view.rs `render_help`, centred, `keys — <page>`, compare/export panels own the Graph page's keys) render from. Part 12: the Doctor tab's `f` fix checklist (doctor_fix.rs T478) over `DoctorWeb.planHere`/`applyHere`, the web page's pair: Space toggles, `k` keeps a copy, Enter then `y` writes the confirmed token, a stale preview is replaced and needs its own `y`; a write re-reads the model. Step 8: `/ws` `doctor` (mod.rs `doctor_reply`, `doctorplan`/`doctorfixed` frames over `DoctorWeb.planHere`/`applyHere`) and the Doctor page's "Fix selected" panel (DoctorFix.tsx, fixState.ts as `Fix.kt`): checkboxes, keep this copy, the diff, confirm with the plan's token, a stale preview replaced. Step 8, part 2: the snapshot's `doctor`/`doctor_pending` (model `doctor_for_snapshot`: `DoctorPage` over `Page.page` on a Background worker, 30 s TTL, keyed on the doctor paths, `doctor probe failed` in `error`), the Doctor page (Doctor.tsx: summary tiles, checks, hooks bars, proxy chains, MCP servers, environment, instructions, agents × modules) and the tui Doctor tab (view.rs `doctor`: `Report.toText()` of the cached report). Step 8, part 3: the snapshot's `tasks` (model/tasks.rs `page` as `TasksPage`: `rtok task list --all` through `TaskProject` on a Background worker, 5 s disk / 120 s remote TTL, `reading`/`unconfigured`/`failed`/`ready`, 50 finished per status, http(s)-only issue links), the Tasks page (Tasks.tsx: board and list views, markers, search, detail) and the tui Tasks tab (view.rs `tasks_page`: the snapshot's `tasks.text`). Next: Stats/Skills once `rtok stats` and the skills page are ported.

## Audit (after #186) — gaps against the Rust SPA and TUI
Snapshot fields, `/ws` actions and every TUI tab are ported. The web Graph page still lacks the parts of
`web/src/pages/graph3d` the TUI already has:
- [x] health ring words, grouped alerts and alert toasts logic (health.ts, alerts.ts) — `GraphHealth.kt`
- [x] drill state in the URL, breadcrumbs, editor links (drillState.ts) — `DrillState.kt`
- [x] drill view and panel (DrillView.tsx, DrillPanel.tsx) over the `graph` reply — `GraphDrill.kt`, `DrillModel.kt`; the node list is the click target (the SVG has tooltips only)
- [x] compare panel and `markGraph` (ComparePanel.tsx, compare.ts) over the `diff` reply (refs or a saved export) — `Compare.kt`, `GraphDrillPanels.kt`
- [x] export menu (JSON/SVG, overview/symbols, focus) and imported view (ExportMenu.tsx, ImportedView.tsx); PNG waits on Ivan
- [x] projects with health rings, `drill →` and the remove dry run (`project_plan`) (ProjectsOverview.tsx, HealthRing.tsx)
- [x] 2D scene (Scene2D.tsx, layout.ts) as our own SVG over a deterministic force layout (`LayoutPoint.kt`, `GraphScene.kt`)
- [ ] deferred: the 3D scene (Scene3D.tsx, stage3d.ts, three.js/d3-force-3d) and the layout web worker

## Compose UI (in progress, Ivan 2026-10-27)

The frontend is being rewritten in Compose Multiplatform, replacing the kotlinx.html UI once it reaches parity.

- Target: `wasmJs` only (the target JetBrains recommends for Compose on the web; Canvas rendering via Skiko).
  Kotlin 2.4.21 (`kotlin("plugin.compose")` at the repo's Kotlin version), Compose Multiplatform 1.12.2
  (runtime, foundation, ui). No Material: own widgets and Canvas charts in `rtok.web.compose`.
- Modules:
  - `:rtok-web-model`: pure UI logic moved out of `:rtok-web-ui` (snapshot frames, page models, drill/compare/fix
    state, formatting) with its tests (run on macosArm64 in the gate). Targets js + wasmJs + macosArm64.
  - `:rtok-web-compose`: the Compose app (shell, routing over the History API, `/ws` link, pages). `webDist` syncs the
    production webpack distribution (`index.html`, `app.js`, two `.wasm` files) into `build/web-dist`.
  - `:rtok-web-ui`: the current kotlinx.html UI, still embedded by `:rtok-web` until Compose reaches parity, then removed.
- Pages done in Compose: Overview (shell + navigation + Overview, PR 1). The rest show "Not ported to Compose yet".
- Serving: open. `embedDir` embeds text files only and the Compose distribution is ~11 MB of binary `.wasm`; embedding
  needs binary (base64) entries in `embedDir`/`Spa` and a size decision before `:rtok-web` switches to `web-dist` of
  `:rtok-web-compose`. `Spa` already maps `.wasm` and now `.mjs`.
