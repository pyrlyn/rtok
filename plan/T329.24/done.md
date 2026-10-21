The Projects panel of the Graph page shows `ProjectRow.backend` (T329.11, already in `/ws` and `snapshot.gen.ts`).

- Selector: a `lsp` or `tags` pill after the index-state pill on every row that has a record; its title carries the detail line and the exact stamps. A row without a record shows nothing, as the list stays one line per project.
- Header: a "graph backend" line for the selected project: the pill (green for `lsp`, amber for `tags` with a reason, grey for `tags` without one), then "<language> server answers" or the reason the project fell back, "checked <age>" and, when the health check will retry, "retries at HH:MM". A project without a record says "no record yet: a graph request under lsp or auto makes one".
- Logic: `backendOf(p, now)` in `projectLogic.ts` builds the tone, label, line and title once for both places; `now` is a parameter, as in `ago`.
- Fixtures: the `?sample` server has three projects now: rtok on LSP, ketch on tags with a reason and a retry time, notes without a record.
- Tests: a Vitest case for the three records on the header, the selector list expectation updated, and stories `BackendLsp`, `BackendTagsWithReason`, `BackendNoRecord` (axe runs on them in `just spa-stories`).
- Docs: a "Graph backend" bullet in "The graph page" of `docs/commands.md` (en, ru, uk).

Result: `just js`, `just spa-typecheck`, Vitest (`--project unit`, 34 files), `just spa-stories`, `just spa-e2e` and `just check` pass.

Deviations: the record holds the language and a `server` flag, not a server name, so the header names the language ("rust server answers") instead of the binary. A project without a record is stated in the header (the task text said "nothing"; the creator asked for it to be said plainly) and the selector row stays bare. The TUI has no projects page (`src/tui` does not render `ProjectRow`; only the `/ws` model carries it), so there was nothing to mirror.

Status: done 2026-10-10 · Model: Claude Code / sonnet-5.5
