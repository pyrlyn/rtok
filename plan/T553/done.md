Source-confirmed: [project_of_cx](src/plugins/checkpoint.rs#L737) returns git_root's directory basename, while [project::project_name](src/project.rs#L19) resolves origin names and linked worktrees to the main project's identity. Both save_session and offer_session use the former, and [latest_session_note](crates/rtok-store/src/note_store.rs#L320) requires an exact project match. End a session in main checkout `repo`, then start one in its linked worktree `repo-fix`: the saved `repo` handoff is not found under `repo-fix`, although normal memory/session attribution treats them as one project. T133/T154 implemented the shared resolver, but this handoff path still bypasses it.

Execution plan:

1. Add a main/linked-worktree fixture and an origin name different from the checkout basename. Save a session checkpoint in one checkout and restore it from the other.
2. Reuse project::project_name for both saving and looking up session handoffs. Define a narrow compatibility path for existing basename-scoped handoffs without searching unrelated projects.
3. Exercise both handoff directions, subdirectories and non-git cwd; preserve the opt-in handoff flag, session-local compact restore and injection budget.

Check: linked worktrees share the intended project handoff consistently with ordinary memory, unrelated project notes remain excluded, and `just check` passes. Source-derived reproduction; no real agent session was started.

**Guard/encoding audit (T554).** T555–T560 were confirmed against `eb13dd7f`
(product source unchanged from `0b14b88e`). T555–T559 are guard behavior defects;
T560 is explicitly a test-oracle defect, not a production decoder failure.
Each was compared with completed T55.8/T55.9/T57.1/T322/T445/T62.1/T468 and
the active backlog. The related features do not track these exact triggers.

Result: Session checkpoint handoff resolves the project through `project::project_name`, with the old git-root basename as a fallback for existing handoffs.

Check: `session_handoff_follows_the_shared_project_across_worktrees` failed before and passes after.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
