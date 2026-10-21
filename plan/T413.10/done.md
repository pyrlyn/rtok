Verify first whether Amazon Q Developer CLI is still maintained or replaced by the Kiro CLI; if replaced, close this sub-task into T413.4 and say so in `done.md`.

Done: closed into T413.4 without code. Kiro's own migration guide says "Kiro CLI is the next update of the Q CLI", that new features and fixes ship only in Kiro CLI, and that the one-time migration copies `~/.aws/amazonq/mcp.json` to `~/.kiro/settings/mcp.json` (https://kiro.dev/docs/upgrade-guides/migrating-from-q/, page updated 2026-08-04, checked 2026-10-12). A separate `amazonq` host would write a file Kiro CLI already copies, so T413.4 owns the CLI. Facts recorded in `research.md` §32.10.

Check: nothing to run; docs-only change.
