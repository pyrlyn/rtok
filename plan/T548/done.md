Confirmed in source and a function probe: [user_prompt](src/plugins/checkpoint.rs#L311) keeps private-tagged text, [render](src/plugins/checkpoint.rs#L122) reproduces it, and [write](src/plugins/checkpoint.rs#L687) passes the rendered body directly to upsert_note. Neither [Runtime::upsert_note](src/plugin.rs#L468) nor [Store::upsert_note](crates/rtok-store/src/note_store.rs#L155) strips it. A compact user record containing `use <private>PRIVATE_SENTINEL</private> briefly` therefore persists the sentinel in checkpoint/session notes and exposes it again on restoration. This violates the private-tag invariant in `src/plugins/memory/AGENTS.md`, despite ordinary mem_save/import already redacting.

Validation: an offline Rust probe compiled the verbatim checkpoint extraction/rendering code and asserted that the private sentinel survives. Persistence/restoration is source-confirmed through write → upsert → render_offer; no live hook or database integration was run.

Execution plan:

1. Add PreCompact and SessionEnd fixtures with private text in prompts and captured errors; assert the stored note and restored injection never contain the sentinel.
2. Reuse the existing private-tag scrubber at the checkpoint text boundary before truncating captured strings, so a closing tag beyond the prompt/error length limit cannot evade redaction.
3. Cover mixed-case, multiline and long private blocks; retain ordinary prompt/path/action output, budgets and byte stability for nonprivate fixtures.

Check: private content is absent from both persisted and restored checkpoint/session bodies, ordinary memory redaction remains intact, and `just check` passes.

Result: Checkpoints pass through `memory::redact_private` (extracted from `strip_private`) before they are stored and before they are restored.

Check: `private_text_never_reaches_a_stored_or_restored_checkpoint` failed before and passes after.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
