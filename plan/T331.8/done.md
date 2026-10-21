Part of T331. The T331.1 and T331.2 checks for the hosts whose hooks live outside JSON: Kimi (`config.toml` `[[hooks]]` blocks), CodeWhale (`[[hooks.hooks]]`), Codex (`config.toml`) and any other host whose installer writes hooks in TOML. Each shape maps to the same `Entry` (event, matcher, command, key path), read through the TOML library the project already uses, so the same classification applies and the entry path names the TOML table. Depends on T331.2.

Check: one mocked scenario per TOML shape (broken, valid, unverified, an unparsable file reported and left alone); `just check`.

Execution: `doctor::hooks::host_sources` also returns the `config.toml` of Kimi, CodeWhale and Codex (the three hosts with a documented TOML hook shape; any other host's TOML is not guessed at), tagged with a `Format`. `scan` parses a TOML file through `toml_edit` and the existing `agents::mcp::toml_item_to_json` (extended to convert arrays of tables) into the same JSON value and builds the same `Entry`, so `classify`, the duplicate check and the report are unchanged. Kimi `[[hooks]]` and CodeWhale `[[hooks.hooks]]` are flat `{event, matcher?, command}` lists (paths `hooks[i]`, `hooks.hooks[i]`); Codex's `[[hooks.<Event>]]` with `[[hooks.<Event>.hooks]]` has the settings.json shape and reuses `entries` (source: https://learn.chatgpt.com/docs/hooks, read 2026-10-03).

Result: broken, suspect and unverified hooks of the three TOML hosts are reported like the JSON ones, an unparsable TOML is `unreadable-config` and left alone. None of them is fixable: `doctor --fix` edits JSONC only, so it names them as "TOML hook files are not edited yet" and writes nothing; a TOML editor for `--fix` is a later task.

Model: Claude Code / sonnet-5
