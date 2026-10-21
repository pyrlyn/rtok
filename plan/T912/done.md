T672 prints 8 hex chars on the `[rtok …]` trailer. The guides, the cmd README and the file `rtok config init` writes still said `<id12>`.

They now say `<id8>`. `rtok expand` resolves that prefix. The Russian and Ukrainian copies of `docs/config.md` and `docs/cmd-rules.md` match. Older done cards keep the wording they shipped with.

Check: `tests/trycmd/config-init.toml` matches `crates/rtok-config/default.toml` on the trailer comment; `docs/config.md`, `docs/cmd-rules.md` and `src/plugins/cmd/README.md` say `<id8>`, and so do `docs/ru` and `docs/uk`.

Status: done 2026-10-19
Model: Cursor / grok 4.7

Creator-reported: `rtok agents update codex --force` lists only the CLI. The Codex desktop app ships as `/Applications/ChatGPT.app` (bundle id `com.openai.codex`; older builds `Codex.app`) and reads the same `~/.codex/config.toml`, but `VARIANTS` in `src/agents/codex/mod.rs` has only `Kind::Cli`.

Result: `Codex` gets a `Kind::Desktop` variant "Codex App" probing `ChatGPT.app` and `Codex.app` in `/Applications` and `~/Applications`, and `shared() = true`, so one apply covers both (the second block prints "same files as above"). Fail-first test `the_desktop_variant_is_detected_from_its_app_and_shares_the_cli_files` (index out of bounds before the fix); `kinds("codex")` is `[Cli, Desktop]`; restart's expected-name table maps it to `ChatGPT`, so a running app is quit and reopened around a changing write like other desktop hosts (`--no-restart` skips it). `rtok doctor`'s MCP duplicate check walked every variant, so a shared host (Cursor on main, Codex now) reported each duplicate twice; a source is now deduplicated by host, file and key instead of by variant set (`a_shared_file_host_reports_a_duplicate_once`, 4 problems before the fix). Source rows in research §40.1; Desktop rows in `docs/agents.md` (en/ru/uk). Live check: `rtok agents update codex --force --dry-run --no-restart` from the branch build lists `Desktop: Codex App — /Applications/ChatGPT.app`.

Model: Claude Code / claude-opus-5-5
