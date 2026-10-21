Confirmed on Unix with actual SDK functions: [link_target](crates/rtok-agent-sdk/src/lib.rs#L327) stops after 40 unsuccessful resolution attempts and returns the still-unresolved symlink. [write](crates/rtok-agent-sdk/src/lib.rs#L309) then calls [write_atomic](crates/rtok-agent-sdk/src/lib.rs#L352), whose rename replaces that symlink with a regular file. For cycle-a -> cycle-b -> cycle-a, write with backups enabled succeeds, replaces cycle-a, and makes cycle-b point to the new content; backup was skipped because exists() follows the cycle and returns false. This violates the SDK promise to preserve dotfile-manager symlinks. Existing dangling-link support does not cover loops.

Execution plan:

1. Add self-loop and two-link-cycle scratch fixtures; assert SDK write returns an error and preserves every link target byte-for-byte.
2. Return a Result from target resolution and reject cycles, exhausted hop limits and unresolved non-missing-path errors before atomic replacement; distinguish a supported dangling target from an unresolved link.
3. Cover valid relative/absolute link chains, a dangling final target with an existing parent, a missing target parent, dry-run and backup-enabled writes. Do not weaken valid dotfile-manager support.

Check: cyclic or over-limit chains never become regular files and produce a clear error, supported acyclic targets still update without replacing their links, and `just check` passes. The destructive behavior was reproduced only on isolated Unix fixtures.

Result: A symlink cycle (or a chain past 40 links) fails the write and leaves every link alone. After merging T640 the refusal comes from `fs_kit::write`, which resolves the chain itself.

Check: `write_refuses_a_symlink_cycle_and_leaves_every_link_alone`, `write_follows_a_chain_of_links_to_the_real_file`, `write_through_a_link_into_a_missing_directory_fails_and_keeps_the_link`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
