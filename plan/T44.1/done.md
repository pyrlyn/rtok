**T44.1 Backup once per content** · P1, 1/5 · `crates/rtok-agent-sdk/src/lib.rs`
Do: `rtok agent setup` and `remove` copied every host file to `<name>.bak-<ts>` on every run, so an unchanged file collected one identical copy per run. `rtok_agent_sdk::backup` now reads the `<name>.bak-*` siblings first and returns `None` when one already holds the same bytes, whatever its suffix; changed content still gets its own copy, and another file's copies never count.
Check: unit test `backup_skips_when_an_identical_copy_exists_under_any_name` (same bytes → `None`; renamed identical copy → `None`; same size, different bytes → new copy; other file's copy ignored); `tests/agent_remove.rs` still green (setup then remove keeps two distinct copies, dry-run takes none).
Status: done 2026-09-16 · Model: Claude Code / Fable 5.1
Evidence: `cargo nextest run -p rtok-agent-sdk` 14 passed; `cargo nextest run --test agent_remove` 7 passed; fmt + clippy `-D warnings` clean on the crate.
