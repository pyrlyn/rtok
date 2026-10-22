Found 2026-10-02 in `~/.rtok/errors.log`: `plugin/guard: PreToolUse panicked: start byte index 2 is not a char boundary; it is inside '–' (bytes 1..4 of string)` (2026-09-28 12:27:35) and `… start byte index 1 … inside '⌘' (bytes 0..3 of string)` (2026-09-29 07:40:06). The hook fails open, so the guard is skipped for those calls. `segments` and `strip_wrap` in `src/plugins/guard/mod.rs` cut only at ASCII bytes, so the slice is elsewhere on the guard path (a fixed `[1..]`/`[2..]` or a byte offset from a helper it calls).

Done when: the panicking slice is found (recover the two payloads from the store's `call_io` at those timestamps), slicing on that path is char-boundary safe, and nothing else in the guard plugin slices at a computed byte offset without a boundary check.

Check: regression tests with commands that start with `–` and `⌘` (and a multi-byte char right after the first byte) fail before the fix and pass after; `just check`.

Execution plan: (1) find the panicking slice on the guard `PreToolUse` path (`src/plugins/guard/mod.rs` and every helper it calls) by a test that feeds commands starting with `–`/`⌘`; (2) make the slice char-boundary safe at that layer and audit the plugin's other computed-offset slices; (3) regression tests red → green; `just check`.

**Result (2026-10-02).** The bad slice was `agents::cmd_stem` (`src/agents/mod.rs`, last 4 bytes for the `.exe` check), called by the guard's `writer_marker`/`stem_read_only`; #535 (2026-09-30) had already fixed it with `get(cut..)`. #607 adds the guard-level regression `non_ascii_input_never_panics_in_pre_or_post_tool` (20 non-ASCII inputs × Bash/Read/Edit/Write/Grep/Glob/Skill, pre and post); red with the old slice (exact logged message), green now. No other unsafe slice in the guard path.

Status: done 2026-10-02
Model: Claude Code / claude-sonnet-5-5 (reviewed by Claude Code / claude-opus-5-5)
