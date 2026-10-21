Source audit only; do not change product code. Trace Doctor web plan/apply selection and junk clear's item planning, protected-path recheck and removal together with their CLI/TUI callers. Check the existing T331 and T330 safety promises before recording any defect.

Execution plan:

1. Trace `doctor::web::{plan,apply}`, `prepare`, `fix::candidates`, `fix_found` and `fix_file`. In a scratch-only machine fixture, plan one fixable broken hook, add a different fixable broken hook before submitting the prior selection, and observe whether the new finding is selected despite not appearing in the confirmed diff; source-trace whether that fresh selection reaches the writer. Compare same-file content re-read protections, changed-file behavior, default toggles and explicit confirmation in T331.6/.7/.12; distinguish fresh discovery from an edit to an already-planned file.
2. Trace junk scan → plan → confirm → `protected`/`recheck` → `remove` for eligible cache/build/extra directories. In an isolated tree, place a package-manager lockfile below an explicitly configured extra cache root; observe whether the root is protected before recursive removal. Source-trace the separate tagged-cache route. Compare direct lockfile, nested lockfile, settings/store descendants, `CACHEDIR.TAG`, and `[agents.junk].exclude` cases.
3. Deduplicate exact behavior against T331's web/terminal selection and file re-read guarantees, plus T330.4/.5.1/.6/.7 and T479. Record that T330.4 says a path that “is or holds” a package-manager lockfile is refused and that T330.5.1 exclusions protect items that match or hold a match; check whether existing tests cover nested locks or a new finding appearing between web plan and apply.
4. For each source-confirmed gap, preserve a minimal fixture and evidence, then add a separate sequential fix card with the protected-data boundary, expected preview/confirmation behavior, implementation steps and acceptance checks. Leave unresolved hypotheses as audit notes.

Check: fixtures remain in scratch only, existing positive/negative selection and cleanup cases remain explicit, plan/todo IDs match, `git diff --check` and scoped `just check` pass. No host app, real home directory or product source is touched.

Result: reviewed Doctor web candidate/default selection and the immediate fix-file reread, plus junk extra-cache planning, recheck and recursive removal. Filed T576 for a newly discovered fix becoming selected after the prior preview and T577 for nested package-lock deletion. Compared against T331.12 and T330.4/.5.1; the latter promises protection for paths that hold a lockfile, while existing direct-lock coverage does not cover a recursively removed parent.

Evidence revision: `5a239604c220da9bef68ad9146d2e8d3315279ae` (the audited product source is unchanged from the pre-merge revision). An isolated Rust probe compiled extracted `prepare`/shared/default-selection functions with fixture discovery/removability stubs; it selected A before rescan and A+B after rescan. The junk probe used actual recheck/newest/usage-walk/remove functions and the permanent-discard branch, with a held-reason stub unreachable and a synthetic timestamp outside the settle window. It showed a direct Cargo.lock protected, an eligible extra cache root retained, and its nested Cargo.lock deleted. This did not exercise host parsing/writes, CLI or trash integration; no real home or host app was touched.

Validation: `CHECK_BASE=1d5889ab36b485befdda0355687a394bf8380077 mise exec -- just check` passed; the selective gate found no code changes, so no Cargo tests were selected. `git diff --check` passed, and active plan/todo IDs matched at 104 unique entries.

Status: done 2026-10-11
Model: OpenAI / GPT-6
