# Web UI and dashboard port (`rtok web`, `rtok dashboard`, `rtok tui`)

Port of `src/web` (axum server + WebSocket protocol), the React SPA in `web/` and `src/tui` (ratatui).
Previously excluded from the port (port-plan.md); now in progress in small steps.

## Plan
1. `:rtok-web` core (this step): `WebToken` (src/web/token.rs, S2 token file, 0600, constant-time compare,
   Bearer/`?token=`) and `checkRemoteBind` (`RTOK_WEB_ALLOW_REMOTE`).
2. `/health`, `/auth`, SPA static serving over `:http-server` (Ktor CIO), `rtok web --host --port` CLI.
3. WebSocket on `:http-server` (`ktor-server-websockets`, same Ktor version), origin check, snapshot ticks
   with build coalescing (T206), `protocol.rs` frames via kotlinx.serialization.
4. Operator model (`src/model`, D23) shared by web and TUI: snapshot built from `:rtok-store`
   (calls, usage, sessions, projects, tasks, worktrees), config, agents, doctor, junk.
5. Inbound actions: `set`, `expand`, `project`, `doctor`, `junk`, `graph`, `diff`, `export`, `import`, `calls`;
   live graph calls (`live.rs`), calls store/view.
6. SPA: keep the existing React/Vite app from the Rust repo as-is (it only talks to `/ws`), embed its `dist`
   in the binary; a Kotlin/JS rewrite is not worth it until the protocol is complete.
7. `rtok tui` / `rtok dashboard`: the same model rendered in a terminal. Own small `:tui` module over
   termios + ANSI (no stable Kotlin/Native ratatui equivalent; Mosaic is Compose-based and heavy).
