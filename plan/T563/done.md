Source-confirmed: [kv_key](src/plugins/memory/sync.rs#L28) stores a block digest under `memory.sync.sha:<basename>`. Sync A/CLAUDE.md with one note set, then B/CLAUDE.md with another, using the same store. The second sync replaces the first file's digest. Re-syncing unchanged A now [fails as hand-edited](src/plugins/memory/sync.rs#L93); removing B also deletes the shared guard state. T69.6 established the hand-edit guard but does not cover this cross-file identity collision.

Execution plan:

1. Add a two-directory fixture sharing one store; sync different blocks and verify each file can be re-synced without force.
2. Derive the state key from a stable normalized absolute destination identity, consistently handling aliases and not falling back to another file's basename digest.
3. Cover removal of either destination, same-file relative/absolute spellings and legacy-key handling without falsely trusting unrelated host content.

Check: one destination's sync/removal cannot invalidate another's protection; genuine hand edits still require force; `just check` passes.

Result: The memory-sync guard is keyed by the resolved destination path (parent canonicalised via `crate::fs::canon`). A digest under the old basename key is still read, but only vouches for a block whose bytes match.

Check: `each_destination_keeps_its_own_hand_edit_guard`, `a_symlinked_spelling_of_the_same_file_shares_its_guard`, `a_legacy_basename_digest_is_read_but_never_trusted_blindly`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
