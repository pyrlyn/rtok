Found by T82's first run on current main (ci run 35573995011, `windows-latest`, debug build): 104 of 117 new failures are the spawned `rtok.exe` dying with `thread 'main' has overflowed its stack`, exit `0xC00000FD` (-1073741571) — on `report`, `stats`, `logs`, `config init`, `plugins`, `info`, `mcp`, `agents install`, `hook PreToolUse` and `hook PostToolUse`. The hook crash breaks "fail open" on that platform. Cause: Windows reserves 1 MiB for the main thread where Linux and macOS give 8 MiB, and `cli::run()` is one function whose ~85-arm `match` keeps every arm's locals in a single debug frame. The shipped `x86_64-pc-windows-msvc` release build was not tested; its frames are smaller, but nothing guarantees the margin.

Plan:
1. `build.rs`: for a Windows target emit `cargo:rustc-link-arg-bins` with an 8 MiB stack reserve (`/STACK:8388608` on msvc, `-Wl,--stack,8388608` on gnu) — parity with the Unix default. Reserve is address space, not committed memory: no cost on the ≤ 10 ms hook path, no new dependency, and it holds for `cargo install` and `dist` builds alike (a `.cargo/config.toml` rustflag would not).
2. Verify on the T82 scratch branch: the `windows` job no longer shows `overflowed its stack` / `-1073741571` anywhere in its log.
3. Splitting `cli::run()` into per-command functions is the structural fix and stays out of scope (≤ 200 LOC rule); note it in `ideas.md` only if the 8 MiB reserve proves insufficient.

Check: `windows` job log of a `ci` run on the change has 0 occurrences of `overflowed its stack`; `just check` green on macOS (the script is a no-op off Windows).

Extra tests (creator request 2026-09-21): an integration test that runs in the `windows` job: `rtok hook PreToolUse` and `rtok hook PostToolUse` on garbage stdin exit 0 with `{}` — the fail-open rule asserted on the platform that broke it.

Check result (2026-09-21): ci run 35573995011 (before) — 117 failed, 104 of them `overflowed its stack` / `-1073741571`. ci run 35576438155 (after, same tree plus `build.rs` and `.gitattributes`) — 4 failed, 0 occurrences of either string in the `windows` job log. Local `rustfmt --check build.rs` and `cargo clippy -p rtok --bins -- -D warnings` clean; the branch is a no-op off Windows. The shipped release build was not tested before or after — the reserve applies to it as well.
