Same creator request as T246.1: `agents remove <host>` (and the plugin-supersedes strip) takes back only the hooks rtok wrote; an rtok hook the user edited is asked about.

Result: `claude::strip_ours` — shared by claude `settings.json`, codex `hooks.json` and zcode `hooks.events` — takes the host's `entries`, timeout key and timeout. An rtok hook still exactly `{type, command, <timeout_key>: timeout}` on a listed `(event, matcher)` pair goes; any other rtok hook goes through the new `rtok_agent_sdk::keep_edited` (also used by `unregister_owned` now): `?` on a dry run, `--yes` or a yes removes it, else `leave hooks.<event> <matcher> in <file> (changed by you; remove by hand)`. `Apply::writes` now skips a report of only `leave`/`?` lines, so a kept edit never rewrites the file. cursor, gemini, kimi and codewhale have their own hook shapes and moved to T246.6.

Check: `claude_remove_asks_before_taking_an_edited_hook` (an edited timeout stays, the rest go, a second remove writes nothing, `--yes` takes it) and `a_leave_only_report_writes_nothing`; SDK 27/27, `agent_remove`/`agents_install`/`singleton`/`cli_trycmd` 32/32; `just check` green.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
