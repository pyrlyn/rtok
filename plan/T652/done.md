Installing or updating rtok with backup disabled used to treat an unreadable host config as missing and replace it, dropping every other MCP server. The disk reader now returns the read error before any edit. A missing file can still be created, and a readable foreign entry is kept.

Landed in `3c5c790d` (#1048). This card only records that the check already holds on main.

Check: an unreadable existing config never gets replaced or reported as installed, including when `[setup].backup = false`; a missing config can still be created; readable unrelated entries survive; `just check` passes.

Check result: `unreadable_host_config_is_never_replaced` (mode `000`, not root) returns an error and the original bytes are unchanged after permissions are restored. `absent_host_config_is_created_and_readable_foreign_entries_survive` creates a missing file and keeps the `other` server next to `rtok`.

Status: done 2026-10-19
