Part of T331. The web doctor page (T310.7) lists the fixable items of `rtok doctor --fix` with the same defaults as the terminal checklist (shared project files unselected), lets the user toggle them and change the kept copy of a duplicate, shows the diff per file, and writes only after a confirmation, through the same `doctor::fix` engine and its refusals. Depends on T331.7 and T310.7.

Check: the selection and refusal scenarios against the page's backend with a mocked machine; `just check`.

Result: the web doctor page has a "Fix selected" panel. `/ws` takes `{"doctor":{"action":"plan"|"apply","selection":{keep,toggled}}}` and answers `doctorplan` (items, per-file diff, refusals) or `doctorfixed` (the report and exit code). The server keeps no session: every request carries the kept-copy swaps and the toggles relative to the defaults and the checklist is rebuilt each time, so a stale list cannot be applied. Only `apply` writes, through the same `fix_found` pipeline (backup into `_backup/`, atomic write, re-parse guard, the same refusals). The terminal and the web share one default-selection rule (`checklist::shared_in`, `checklist::defaults`). The origin guard of the `/ws` upgrade covers the new messages like `set`. Tests: 5 Rust unit tests on the mock machine (`doctor::web`), a real-socket e2e (`tests/web_e2e.rs`), 6 reducer and 4 api/page Vitest tests, and a browser story with a play function (select, diff, confirm) on the mocked machine in `web/src/api/sampleDoctor.ts`.

Status: done 2026-10-03
Model: Claude Code / claude-sonnet-5-5
