A store that fails to open renders an empty web page, and a dropped WebSocket leaves the last frame on screen with no hint (survey 2026-09-17, `src/web/mod.rs`, `crates/rtok-webui`); the TUI shows a doctor failure string but no store error line.
Done when the snapshot carries an `error: Option<String>` the model fills when the store or doctor fails, both surfaces render it as a banner instead of empty pages, the web client reconnects with capped backoff and shows "reconnecting" until the next frame, and `tests/web.rs` plus a TUI `TestBackend` test cover the unreadable-store case (a `Vfs`-style fixture: point `db_path` at a directory).

**Result (2026-09-18).** Commit `45c4531`. `error` field on web snapshot; error banner and WebSocket reconnect in `rtok-webui`; TUI store-error line; `tests/web.rs` unreadable-store case.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
