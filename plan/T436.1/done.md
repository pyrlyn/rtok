Creator request 2026-10-07: the same as T436, in the `rtok web` SPA. Only page loads showed a spinner; the plugin switch, project select/link, doctor plan/apply and Calls expand only disabled their buttons.

Done: one `Spinner` in `web/src/ui` (stories), and `Loading` draws through it. Every request-sending control spins from click to answer, is disabled meanwhile and sets `aria-busy`: the plugin switch, project select/link/unlink in `Projects.tsx` and `ProjectsOverview` (the graph canvas and menu get a "Selecting…" status line), doctor Confirm and "Fix selected", Calls expand. A switch stays where it was until the server answers and on a refusal stays and shows the error. `api.set` and `api.project` now settle on the next snapshot frame, reject on a message frame, or time out after 30 s (`WRITE_TIMEOUT_MS`); `messageKey`/`useServerMessage` are gone, pages show `error.message` through `Result`. `operations.ts` maps verbs to icons with a kind fallback, same stems and order as the CLI table; `Button` and `Result` carry the operation's icon; 17 SVGs added under `brand/icons/ui/`. `operations.test.ts` parses `OPERATION_ICONS` out of `src/ui/style.rs` and compares the stems. Known limit: a periodic snapshot already in flight at the click can end a spinner a few ms early; the next snapshot shows the true state.

Check: web unit tests 208 passed; `just spa-stories` 148 passed (axe); `just spa-e2e` 20 passed, two new tests hold the WebSocket with `routeWebSocket` and see the spinner on the plugin switch and doctor apply until the answer; `just check` 2759 passed.
Result: PR #849.
Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
