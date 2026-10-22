First of T246.1–T246.4 (creator request 2026-09-24): removing rtok takes back only what rtok wrote, and asks about what the user changed.

Plan: new `rtok_agent_sdk::unregister_owned(apply, path, key, name, ours, is_bin)` applies the three outcomes and returns before any write on a `leave`; `mcp_entry` is the entry `register_mcp` writes; new `confirmed` prompt (`[y/N]`) shares `ask` with `accepted`. In rtok, `agents::unregister_ours` passes `is_rtok_bin`, and `unregister_mcp_ours` covers the `mcpServers` stdio entry. Callers now: claude (CLI and desktop), cursor, codewhale. Tests: SDK unit tests (outcomes, `--yes`, dry run, `[y/N]` default) and `tests/agent_remove.rs` (claude: edited entry kept on a pipe, removed with `--yes`, a foreign `rtok` server left).

Result: `rtok_agent_sdk::unregister_owned` judges the entry named `rtok` before it drops it. Equal to what the installer writes (any rtok binary path counts as the same): removed. Runs rtok but differs: `? remove mcpServers.rtok in <file>? you changed it [y/N]` (`confirmed`, default keep). `--yes` removes it; no terminal keeps it and reports `leave … (changed by you; remove by hand)` without writing; a dry run reports `? … (changed by you; remove asks)`. Does not run rtok: `leave … (not rtok's; remove by hand)`. `agents uninstall` gained `--yes` for this. Claude (CLI and desktop, including the T243 plugin-supersedes strip), Cursor and CodeWhale go through it; the other hosts are T246.2.

Deviation: 15 files, because the new `--yes` flag regenerates five `tests/trycmd` completion/help snapshots.

Check: `cargo test -p rtok-agent-sdk` 27 passed; `cargo nextest --test agent_remove --test agents_install --test claude_plugin --test cli_trycmd` 32/32; `just check` green.
