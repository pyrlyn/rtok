# checkpoint and inject plugins

Ports of `src/plugins/checkpoint.rs` and `src/plugins/inject/mod.rs` (plan T2.4/T2.5).

**checkpoint** (`rtok/plugins/checkpoint/`): on PreCompact the transcript JSONL is streamed
(`rtok.sys.forEachFileLine`, Rust `BufRead::lines` semantics) into last 20 typed prompts (host-written
records dropped, `<system-reminder>` and `<private>` stripped), touched files (edited/created/deleted
before read, Read ranges, `rm`/`git rm` via our `rtok.sys.Shlex`), last 8 error lines and loaded skills.
Stored as note `checkpoint:<session>` (project `rtok`); SessionEnd stores `session:<session>` under the
project name (handoff). Restore fits `[plugins.memory] checkpoint_tokens`. A byte prefilter skips
lines that cannot contribute; tests prove it a superset.

**inject** (`rtok/plugins/inject/`): SessionStart offers the checkpoint (compact) or the project
handoff (startup, `startup_recall`) at priority 9, then the configured modes (`modes/*.md` embedded
byte-for-byte, overridable in `modes_dir`, read only as far as the budget needs). `apply` emits offers by
priority until `[plugins.inject] budget_tokens`, cuts the first that overshoots and lists the rest as
`dropped:<plugin>:<tokens>`.

## Tests
`CheckpointTest` 27 (24 Rust ports + Shlex, `{:.1}` rounding, stream/in-memory parity), `InjectTest` 9.
Pending Phase 6 (need the hook dispatcher): `session_handoff_follows_the_shared_project_across_worktrees`,
`pi_and_opencode_compact_restore_bytes_match_claude`, the timing half of
`session_end_on_a_large_transcript_is_bounded`, and the UserPromptSubmit halves of the inject mode tests.
