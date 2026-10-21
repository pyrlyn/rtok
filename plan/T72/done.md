Creator 2026-09-19. `rtok agents info <host>` prints the same blocks as `agents list` for that host (`--json` too). `agents list` probes hosts in parallel (`std::thread::scope`, no new crate). Install and uninstall copy configs into a sibling `_backup/` directory and skip when any file in that folder already has the same bytes, name ignored. `agents remove` is now `agents uninstall` (`remove` stays a clap alias). A stderr spinner (`render::loader`) runs for list/info/install/uninstall; indicatif stays silent off-TTY.

**Result (2026-09-19).** `visit_hosts` in `src/agents/mod.rs` walks hosts in parallel; `list` / `info` / `model::agents_listed` share it. CLI: `AgentCmd::Info`, `Uninstall` with visible alias `remove`. SDK `backup` writes `_backup/<name>.bak-<ts>` and skips when any regular file in that folder is byte-equal. `render::loader` on stderr for list/info/install/uninstall (silent off-TTY).

**Check:** `info` of one host has that host's block and not another's; unknown host refused; identical bytes in `_backup/` under any name skip a new copy; `cargo clippy -p rtok -p rtok-agent-sdk --all-targets -- -D warnings` green; agents unit tests, `agents_install`, `agent_remove`, `cli_trycmd`, `surface_parity`, `host_docs` pass. One unrelated flaky `store::tests::concurrent_opens_of_a_fresh_store_all_migrate` in full nextest.

---
