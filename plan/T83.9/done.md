Connecting to an unreachable endpoint should fail fast (the point of the test — hooks must stay under budget even when otel can't be reached), but on Windows it apparently blocks for 131 s. Likely a difference in how Windows resolves/connects to an unreachable address (DNS or TCP connect timeout defaults) versus Unix. Decide whether the otel client needs an explicit Windows-safe connect timeout or the test's "unreachable" address needs to be one that fails fast cross-platform. One family split out of the original T83; see T83.2 for the closing criterion.

Check: the test passes well under its slow-timeout in the `windows` CI job; `just check` stays green.

Do (Claude Code / claude-opus-5-5, 2026-09-24): not a connect timeout — the same inherited-pipe hang as T83.3. Every hook (`rtok hook Stop` here) calls `otel::export::spawn_child`, which spawns a detached `rtok otel flush --coalesce` with `Stdio::null()` on all three streams. On Windows that child still inherits the hook's own inheritable stdout/stderr handles, i.e. the write end of the test's `wait_with_output()` pipe, and keeps it open until the flush gives up on `127.0.0.1:9` (Windows retries a refused loopback connect for about two seconds per attempt). Each of the test's samples therefore waits for the flush child instead of the hook, and the retry-to-deadline loop runs into the 131 s timeout. Fix: `rtok_sys::stop_inheriting_own_stdio()` right before the spawn in `spawn_child`, as `demon::start` does; no-op on Unix. Removed the test from the `cfg(windows)` override in `.config/nextest.toml`.

Check result: `just check` green on macOS; PR #336's `windows` CI job passes the test (ci run before the rebase).

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
