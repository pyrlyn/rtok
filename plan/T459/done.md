Cloud review finding: the `rtok-hook` client read all of stdin with `read_to_end` before `Request::encode()`, so a huge payload was allocated in full and copied into the frame; only the resident capped it later (T201, `core.hook_max_input_bytes`).

The client has no config, so the 8 MiB default now lives in `rtok_hook::HOOK_MAX_INPUT_BYTES` and `core.hook_max_input_bytes` defaults to it. The client reads `take(cap + 1)`. A body over that skips the resident (the connection is dropped unsent) and runs `rtok hook`, which gets the bytes already read and then the rest of stdin, so the configured cap, the `{}` answer and the stderr line stay `rtok hook`'s. A user who raised the cap above the default pays a process start for bodies between the two. `rtok()` now takes a reader instead of a byte slice to carry both cases.

Check: `the_client_does_not_send_a_body_over_the_cap_to_the_resident` (a fake resident is never sent the body and the client prints `{}`) fails on the unfixed client (it printed the resident's answer) and passes now; `just check`.

Result: see the commit; `just check` green.
Status: done 2026-10-09
Model: Claude Code / sonnet-5.5
