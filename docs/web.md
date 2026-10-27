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
6. Frontend: a Kotlin/JS module `:rtok-web-ui` (kotlinx.html for the DOM, kotlin-css for styles), speaking the
   same `/ws` protocol and matching the Rust SPA's pages and features. Charts are drawn by our own small SVG
   helpers in Kotlin (no JS chart library); the graph view uses our own Canvas/SVG force layout. The built
   bundle is embedded in the binary as an `Assets` source; `RTOK_WEB_DIST` still overrides it for development.
7. `rtok tui` / `rtok dashboard`: the same model rendered in a terminal. Own small `:tui` module over
   termios + ANSI (no stable Kotlin/Native ratatui equivalent; Mosaic is Compose-based and heavy).

## Status
- Step 2: `rtok web` serves `/health`, `/auth` and the UI files from `RTOK_WEB_DIST` (SPA routing, br/gzip
  variants, ETag, CSP). Until step 6 the binary has no embedded UI, so page routes answer 503 with a hint.
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
  Still to come: `tasks`; then `skills`, `stats`, `services`, `worktrees`, `doctor` and `junk`,
  which wait for the `doctor`, `stats`, `demon`/`otel`, `worktree list` and `junk` ports.
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
