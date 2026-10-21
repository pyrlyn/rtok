**T42 Module status in `agent setup` and `doctor`** · P2, 2/5 · `src/setup/mod.rs`, `src/cli.rs`, `src/doctor.rs`
`rtok agent setup <host>` printed only the installer's diff, and `rtok doctor` counted Claude hooks only; neither said which rtok modules a host carries.
Do: after each host, `agent setup` prints every module (`hooks`, `mcp`, `proxy`, `plugin`) as green `✓ installed`, red `✗ not installed` or grey `− not supported`, for all five hosts; `rtok doctor` prints the same block per host variant under `agents` (plain words, no marks, in the text the PDF/HTML reports and the TUI embed). `setup::{MODULES, supported_modules, module_states, module_lines}` over `installed_modules`; `doctor::Report.agents` reads files only (no `--version` probe). Claude proxy detection now parses `env.ANTHROPIC_BASE_URL` against `[proxy] bind:port` instead of matching `8790` anywhere.
Check: a unit test reads a Claude settings file back as hooks ✓ / mcp ✗ / proxy ✓ (port 9123) / plugin −; `just check`.
Complexity: 2/5
Status: done 2026-09-15 · Model: Claude Code / Opus 5
Evidence: `claude_modules_read_back_hooks_and_a_proxy_on_any_port` green; fmt + workspace clippy `-D warnings` clean; `cargo test --workspace` 428 passed; build-min ok; jscpd within threshold; `agent setup claude --dry-run` and `doctor` in a throwaway `HOME` print the marks for every host.
