Confirmed in the selection path: [Selection](src/doctor/web.rs#L31) carries only kept-copy swaps and toggles, and [prepare](src/doctor/web.rs#L80) rebuilds candidates and default selections on both plan and apply. Preview a removable broken hook A in a user-level settings file, then add another removable broken hook B before confirming the original preview. With the original empty selection delta, B is also selected during apply although the page confirmed only A. [DoctorFix confirmation](web/src/pages/DoctorFix.tsx#L38) sends the deltas without preview identities or file versions. [fix_found](src/doctor/fix.rs#L284) edits the fresh selected findings; [fix_file's reread](src/doctor/fix.rs#L366) compares bytes read within that apply request, not those shown in the earlier preview. T331.12's fresh rescan prevents use of stale findings but does not bind writes to the confirmed diff.

Execution plan:

1. Add a fake-filesystem plan/apply regression: preview A, introduce B, submit the original confirmation and assert B is untouched. Cover insertion in the same file and a newly discovered file, plus an unchanged-preview success control.
2. Carry an explicit set of confirmed identities and content/version evidence from the preview into apply. Revalidate the selected entries and affected files before writing; refuse a changed preview and return a fresh plan requiring confirmation. Do not auto-select findings discovered after the preview or treat a reused array position as the same entry.
3. Wire the preview evidence through the web protocol and page state; cover toggles, kept-copy swaps, shared-file defaults, reordered/replaced entries, stale requests and backup/write failures. Retain the immediate pre-write reread as a separate safeguard.

Check: apply never removes an entry absent from the confirmed preview or silently changes the confirmed diff, unchanged selections still work, and `just check` passes. The isolated probe compiled actual prepare/shared/default-selection functions with fixture discovery/removability: the same selection chose A before the rescan and A+B after it. Host parsing and writes were source-traced, not executed; full fake-filesystem and page regressions belong to this fix.

Result: Doctor `plan` returns a token of the selected entries and their diff; `apply` writes only when the rescan yields that token, else returns a fresh plan (`Fixed.replan`) needing its own confirmation. Wired through `/ws`, the SPA and the TUI.

Check: `an_entry_that_appeared_after_the_preview_is_not_removed_by_its_confirmation`, `an_unchanged_preview_still_applies_and_a_missing_or_wrong_token_does_not`, `the_token_follows_the_selection_so_a_toggle_needs_its_own_confirmation`, plus TUI/SPA/e2e counterparts.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
