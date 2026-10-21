**T45.6 Extra hook/expand/guard/read/toon coverage** · P2, 2/5 · `tests/extra_cover.rs` (new)
Do: nine cases through the binary and the library: hook fail-open on garbage and empty stdin, `expand` of an unknown id with `--lines`, the 11-row `plugins` listing, PreToolUse rewrite plus deny-wins merge, a guard deny naming an expandable id, the read cap marker within `max_chars`, toon comma-cell round-trip. The `collapsible_if` lint left by T45.4 is gone.
Check: `cargo test --test extra_cover` (9); clippy `-D warnings` on all targets.
Status: done 2026-09-17 · Model: OpenCode / Muse Spark 1.3 (code); closed by Claude Code / Fable 5.1
Evidence: the file landed inside `a423aca`; `just check` exit 0 (716 passed, clippy clean).
Deviation: committed by another agent's snapshot commit (`a423aca`), not as its own `T45.6` commit; this commit only moves the task to done.md.
