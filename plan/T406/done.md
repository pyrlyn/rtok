From an rtok agent shell, `cargo test --test cli_trycmd` failed cases 6 and 10 of `tests/trycmd/agents-messages.trycmd`: the session's `RTOK_AGENT_ID` (exported through `CLAUDE_ENV_FILE`, T283) reached the `rtok` binary under test, so `agents inbox` read the caller's queue instead of failing. trycmd 1.2.1 has no harness-wide env removal, its `TestCases::env` default overrides a case's own `[env.add]`, and `unsafe_code = "forbid"` rules out `remove_var`.

Done: `tests/cli_trycmd.rs` lists the session env (`SESSION_ENV`: `RTOK_AGENT_ID`, `RTOK_CONFIG`, `CLAUDE_ENV_FILE`); when any of it is set, the `cli` test reruns itself in a child of the test binary with those vars removed and asserts the child passed. A case that sets one of them still gets its own value. `CLAUDE_ENV_FILE` is in the list because `hook.toml` runs `rtok hook`, which appends to that file.

Check: `cargo test --test cli_trycmd` with `RTOK_AGENT_ID` set passes; `just check` green (2392 tests).
