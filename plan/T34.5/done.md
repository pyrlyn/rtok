**T34.5 agent-sdk writes host configs atomically** · T27.0 · `crates/rtok-agent-sdk/src/lib.rs`
Do: `write` truncated `~/.claude.json` and friends in place — a crash mid-write lost the host config, and a symlinked (dotfile-managed) config was replaced by a file.
Check: temp file beside the target, permissions copied, `rename`; the symlink's target is written. Tests `write_leaves_no_temp_file_behind`, `write_preserves_existing_permissions`, `write_through_a_symlink_updates_the_target_and_keeps_the_link`.
Complexity: 2/5
Status: done 2026-09-10 · Model: Sonnet 5 (subagent)
