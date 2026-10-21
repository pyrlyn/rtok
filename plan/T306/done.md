`store::session_live_archives` (T58.2), consumed by `checkpoint::attach_ids`, ordered live archive ids by `archive::ts` — the time a body's bytes were first archived, never re-stamped since `archive` rows dedupe by sha256 — instead of `archive_decisions::ts`, the time this session's own pointer to that body was created. A later turn re-archiving an identical, unchanged body (e.g. re-reading a file) then ranked as the oldest entry, so `attach_ids`'s token budget could drop a genuinely recent archive in its place; the same archive id could also come back twice when two decisions named it.

Check: `store::tests::session_live_archives_orders_by_decision_time_once_each` (new); `cargo test --lib store::` and `--lib checkpoint` pass; workspace clippy (`-D warnings`) and `cargo fmt --check` clean.

Status: done 2026-09-28
Model: Claude Code / claude-sonnet-5
