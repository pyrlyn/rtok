**T45.5 Gates cover tests, webui and examples** · P2, 2/5 · `.jscpd.json`, `examples/mcp_tool.rs`, `tests/trycmd/config-show.*`
Do: jscpd now scans `crates/rtok-plugin-sdk`, `crates/rtok-agent-sdk`, `crates/rtok-webui` and the mirrored tests (`config_coverage.rs`, `surface_parity.rs`) besides `src/`, with the gate still green. `examples/mcp_tool.rs` records one `Measurement` per tool call and asserts exactly one row, like `hello_plugin`. `config show` has a trycmd snapshot.
Check: `just dup` (jscpd) exit 0; `cargo run --example mcp_tool`; `tests/cli_trycmd.rs` with `config-show`.
Status: done 2026-09-17 · Model: OpenCode / Muse Spark 1.3 (code); closed by Claude Code / Fable 5.1
Evidence: the code landed inside `a423aca`; `just check` exit 0 (716 passed, jscpd 70 clones under threshold).
Deviation: committed by another agent's snapshot commit (`a423aca`), not as its own `T45.5` commit; this commit only moves the task to done.md.
