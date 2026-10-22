**T51.4 `rtok wrap -- <agent>`** · P3, 2/5 · `src/proxy/cli.rs`, `src/cli.rs`, `tests/wrap.rs` (new), `tests/trycmd/help.stdout`

From I-12. Pointing a host at the proxy means editing its config; for a one-off run it is simpler to set `ANTHROPIC_BASE_URL` / `OPENAI_BASE_URL` for one process.

Do: `rtok wrap -- <cmd> [args]` ensures the proxy answers `/health` (hand-rolled TCP GET, no new dep — reqwest has no blocking feature), starting it in-process on a background thread with its own runtime when down; execs the child with both base URLs from the same helpers the installers write, inherits stdio, prints nothing itself, and exits with the child's code (signal death → 128+signo; terminal signals already reach the child via the shared foreground pgroup, so no signal crate). Fail open: an unstartable proxy runs the command with the operator's own environment. No long flags, so no new config keys (T12.4 walk skips positionals).
Check: `tests/wrap.rs` — fake agent (`sh -c` / `cmd /c`) echoes both URLs with byte-exact silent stdout, `exit 3` → 3, and the ensured proxy records one `usage` row plus `calls`/`call_io`/`tokens` rows against the Anthropic body fixture.
Status: done 2026-09-17 · Model: OpenCode / Muse Spark 1.3

Evidence: `cargo test --test wrap` 3 passed; `cargo nextest run --test wrap --test proxy --test config_coverage` green; `help.toml` trycmd passes with the new `wrap` line; `cargo fmt --check` clean.

Deviation: the 129-line `src/proxy/cli.rs` bulk landed inside G1 `2b8c266` (T48.3), swept from the shared dirty tree in pre-fix form — HEAD did not compile (E0382 moved `cfg`, missing `anyhow::Context`, two clippy lints). This commit adds the `src/cli.rs` wiring, those fixes, the tests and the help snapshot instead of re-committing the swept lines. Files: 3 source/test + 1 snapshot line. `just check` clippy stays red on another agent's untracked `src/agents/windsurf/mod.rs` (`unused import std::fs`), and `config-show`/`agents_install` trycmd cases fail on other agents' uncommitted host/config WIP — both unrelated to this task and left for their owners.
