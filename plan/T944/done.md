On 2026-10-20 the creator opened `http://127.0.0.1:3333/#/overview` without `?token=`: `/ws`
answered 401 (S2, `docs/web-auth.md`) and the SPA showed only "Offline / Reconnect". A browser
WebSocket cannot read the 401, and a token kept in `sessionStorage` goes stale when `rtok web`
restarts (fresh token per start), which looks the same. The creator asked to cover both.

Done: `GET /auth` (`src/web/mod.rs`) runs the `/ws` gate (`admitted`: token, then `Origin`)
without the upgrade and answers 204, 401 or 403; `auth` is a reserved first segment in the SPA
router. `web/src/api/auth.ts` turns that into a reason: no token → `missing`, `/auth` 401 →
`rejected`, anything else (accepted, server down) → none. `Shell` asks on every close and
`Offline` shows `open "<origin>/?token=$(cat ~/.rtok/web-token)"` with "needs its access token"
or "was restarted and issued a new token"; a real disconnect keeps "The connection to rtok was
lost." The Vite dev proxy forwards `/auth`; `docs/web-auth.md` describes the probe.

Check: `tests/web_token.rs` `auth_probe_answers_the_ws_checks` failed before the route (the SPA
answered 200) and passes after; Vitest `api/auth.test.ts`, `states.test.tsx` and three
`app.test.tsx` cases failed first and pass; two new stories (axe) in `just spa-stories`; the
e2e restart case now sees the plain text while the server is down and the "restarted" hint
once it is back with a new token. `just js`, `just spa-test`, `just spa-stories`, `just spa-e2e`
green; `just check` green except two graph watcher timing tests that fail on this loaded
machine and touch nothing changed here.
