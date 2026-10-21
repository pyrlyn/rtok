Found by a bug-hunt pass over `src/store/**`.

1. `write_archive_file` wrote the content-addressed archive with `fs::write`, which truncates in place: a concurrent writer of the same body or a crash let `rtok expand` read an empty or short file, breaking the lossless rule. It now writes a sibling temp file and renames it over the target.
2. `register_agent` kept `ended_at` on conflict, so a resumed session (same id after `SessionEnd`) never showed in `live_agents` again. The upsert now clears it.

A third audit claim (`symbols::replace_one` keeps stale rows for an emptied file) was a false positive: the delete runs before the empty-rows return.

Check: new `archive_file_write_replaces_the_target_atomically` and `registering_an_ended_agent_again_makes_it_live` (both failed before the fix); `just check` green.

Status: done 2026-10-01
Model: Claude Code / sonnet (reviewed by Claude Code / claude-opus-5-5)
