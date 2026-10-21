`health_stays_fast_while_a_large_request_is_recorded` (`tests/proxy.rs`, T205) asserts every `/health` poll returns in under 250 ms while a 48 MB `compress`-mode request is shaped. On a loaded `macos-latest` runner it took 301.8 ms (run 37334858645, PR #749, which only touched `web/`); the same code passed on the previous run. Done: the bound scales with how slow the runner is, so load alone cannot fail it, and the test still fails when `/health` waits on the recording (the T205 regression it guards).

Plan: measure on the same runtime how long the big request takes to reach upstream; a T205 regression makes one `/health` poll wait for the whole synchronous shaping, i.e. most of that window, while a healthy proxy answers in a small fraction of it. Assert each poll stays under a fraction of the window instead of a fixed 250 ms. Only `tests/proxy.rs` changes.

Check: the test run many times under artificial CPU load passes every time; with `spawn_blocking` replaced by an inline call in `src/proxy/mod.rs` (not committed) it fails every time; `just check`.

Result (2026-10-05, Claude Code / opus-5.5): the loop keeps the slowest `/health` poll and the window from spawning the big request to upstream seeing it, and asserts `slowest < window * 2 / 3`; the failure message prints both and the poll count. Nothing in `src/` changed. Evidence, debug build, 16-core host already at load average 30-40 from other sessions, extra load from `yes` burners: healthy, 40/40 at host load (ratio up to 0.077) and 100/100 with 32-64 burners at load 33-197 (ratio up to 0.39, slowest poll up to 3.2 s; 90 of those 100 had a poll over 250 ms, so the old bound would have failed them). With `spawn_blocking` replaced by an inline `shape_request` call (not committed): 30/30 fail, ratio 0.835-0.995, at host load and with 32-64 burners. `just check` green.

Status: done 2026-10-05
Model: Claude Code / opus-5.5
