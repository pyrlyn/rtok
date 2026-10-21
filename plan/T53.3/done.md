From I-32. On macOS the one binary links Security.framework and CoreFoundation for reqwest's platform verifier, costing about 1.3–1.5 ms of dyld time per hook spawn, as much as the hook's own work.
Done when the creator picks the trade-off (webpki roots with `use_preconfigured_tls` and dead-stripped dylibs, versus a second tiny hook binary), the choice is recorded as a decision, and the hook p95 before/after is measured and stored in `research.md`. Corporate CA support must be documented either way.

**Result (2026-09-18).** Decision D30: webpki + `use_preconfigured_tls`, one binary (second hook binary rejected). Implementation already in `d899760` (`src/tls.rs`, proxy/otel `.use_preconfigured_tls`, rustls 0.23.43, webpki-roots 1.0.9, rustls-pemfile 2.2.0). This branch recorded `otool -L` (Security.framework still linked before and after), hook p95, and `SSL_CERT_FILE` docs. Release `rtok` 25,124,800 → 25,562,032 bytes; dylib set unchanged. Sequential n=200 spawn-to-exit on this machine (1-min load 50): PreToolUse p95 79.71 → 80.82 ms, PostToolUse 66.47 → 92.60 ms — no dyld win while the frameworks stay linked. Corporate CAs: `docs/config.md` (TLS and corporate CAs). Commit `9be22b6`.

**Check:** `cargo test --lib tls` 3 passed; nextest `--test proxy --test otel` 40 passed, 1 skipped.

---
