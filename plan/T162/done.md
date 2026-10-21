Creator question 2026-09-22: a background task ran `sleep 90; cat …/tasks/<id>.output | tail -30` and the Bash card showed no `rtok`. The card shows the model's command, not the hook's `updatedInput`; nothing pinned that this shape is wrapped.

Plan: a unit test in `src/plugins/cmd/hook.rs` for the exact rewrite; an e2e in `tests/commands_e2e.rs` that emulates the host — `rtok hook PreToolUse` on the event, then `sh -c` on `updatedInput.command` with the built binary on `PATH`.

Check: `cargo nextest run -E 'test(sleep_then_piped_cat_is_wrapped) | test(hook_rewrite_of_sleep_cat_tail_runs_through_rtok)'`; `just check`.

Status: done 2026-09-22
Check result: both tests pass; the e2e output starts at `line 21` of 50 and ends at `line 50`, so `tail -30` ran inside `rtok run`. A manual run of the installed hook on the same command printed `rtok run -- 'sleep 1; cat … | tail -30'` and the `[rtok … expand]` trailer. No wrap rule changed.
Model: Claude Code / claude-opus-5
