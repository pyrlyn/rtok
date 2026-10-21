The Playwright helper [`freePort`](web/e2e/rtok.ts#L24) and the Rust e2e helper [`free_port`](tests/web_e2e.rs#L34) bind port 0, close the probe and pass the number to `rtok web --port`. With four parallel workers another process can take the port in between, and `rtok web` exits 1 (`rtok web exited early (1)`, CI on PR #997, `e2e/rtok.spec.ts:17`); worse, the `/health` poll can reach another worker's server. `rtok web --port 0` is already accepted, but the startup line prints `:0` and `/health` reports port 0.

Execution plan:

1. `src/web/mod.rs`: after binding, take the listener's real address and write its port back into the config, so the stderr line `rtok web http://<addr>` and `/health` report the bound port.
2. `tests/web_e2e.rs`: a regression test that starts `rtok web --port 0`, reads the port from the stderr line and checks `/health` answers there with that port (fails before step 1); `start_with` uses the same path and drops `free_port`.
3. `web/e2e/rtok.ts`: the first `start()` passes `--port 0` and reads the port from stderr; a restart (the offline test) reuses that port so the open page reconnects.
4. Verify: `just spa-e2e`, `just check`.

Check: no e2e helper probes a port and hands it to a child; `rtok web --port 0` prints and serves the bound port.

Result (2026-10-12, Claude Code / claude-opus-5-5): `serve` takes the listener's local address after binding and writes its port into the config, so `rtok web --port 0` prints `rtok web http://127.0.0.1:<port>` and `/health` answers with that port. `tests/web_e2e.rs` starts every server with `--port 0` and reads the address from stderr on a thread that keeps draining the pipe; `free_port` is gone. The new `port_zero_reports_the_bound_port` failed before the fix (`rtok web never answered /health` after 20 s, the line said `:0`) and passes after; `--test web --test web_e2e` 22/22. `web/e2e/rtok.ts` spawns with `--port 0` on the first start, takes the port from the same line (racing the child's exit and a 20 s timer), and a restart asks for that port again so the offline test's page reconnects; an early exit now carries the last 20 stderr lines, so a CI flake names its cause. `just spa-e2e` 38/38, then `--repeat-each 3` 114/114 with 4 workers. Not done: a restart on the remembered port can still lose it to another process while the server is down; only the offline test restarts.
