T246.3 did the Claude-shaped hooks (claude, codex `hooks.json`, zcode) through `claude::strip_ours`. cursor (`hooks.json` flat entries), gemini (`hooks.<Event>[]` with its own event names), kimi and codewhale (TOML `[[hooks]]` tables) each have their own `strip_ours`: each compares an rtok hook with the shape its installer writes and hands a changed one to `rtok_agent_sdk::keep_edited`.

Result: each host's `strip_ours` now takes `(apply, path, …, timeout)` and removes per hook. Unchanged means exactly the installer's shape: cursor `{command}` alone; gemini an entry of only `hooks` with the hook `{type, command, timeout}` in ms; kimi a table on a pair `ENTRIES` lists with only `event`, `matcher` (when set), `command` and `timeout`; codewhale a table of only `name = "rtok"`, `event`, `command` and `timeout_secs`. Any binary path counts, as in T246.3. Shared helpers in `src/agents/mod.rs`: `takes_hook` (unchanged → take, else `keep_edited`), `removed_report` and `with_kept`; `claude::strip_ours` uses them too. gemini now drops only rtok's hook from a shared entry instead of the whole entry.

Check: `hook_hosts_remove_asks_before_taking_an_edited_hook` (an extra key on one rtok hook per host: remove leaves it with a `changed by you` line and takes the rest, a second remove writes nothing, `--yes` takes it); `just check` green.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
