Bug. `prepare_args` renames `search` `query` to `pattern` and `docs_query` `question` / `userQuery` to `query`, then `missing_required` checks the canonical key. The advertised schema still required only that key, so a client rejected the alias before the call arrived. `ping` required `agent` although the handler accepts none.

`search` and `docs_query` now advertise `anyOf` of those alias sets (`docs_query` still requires `name`). `missing_required` accepts a call when one branch is present and still names the first branch when none are. `ping` no longer requires `agent`. `file_path`, `hash` and `symbol` `query` are not aliases `prepare_args` renames, so those schemas stay (`read` of `query` still misses `path`).

Check: `advertised_schema_accepts_each_prepare_args_alias` validates each alias payload against the `tools/list` schema with `jsonschema`, and an empty `search` still misses `pattern`. `just check` (full gate, 4185 passed).

Status: done 2026-10-22
Model: Grok / grok-4.7
