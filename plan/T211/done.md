Found 2026-09-22 in the store/accounting pass: `inline_body` (`src/store/mod.rs:1823-1828`) stores bodies under the inline cap through `String::from_utf8_lossy` and hashes the *lossy* text, so `request_sha256`/`response_sha256` are not hashes of the wire bytes and `call_io_request` (:736-750) returns U+FFFD-corrupted bytes as if they were the original request. Consumers like `src/measure/cache.rs:106` see different bytes than the proxy sent; the stored sha cannot verify the true payload. Lossless-by-default holds for archived content but not for inline-kept content.

Plan: store inline bodies as BLOB (or base64 in the TEXT column) with the sha of the raw bytes, keeping the lossy text only as a derived display column; migrate with a nullable column filled lazily on read.

Check: extend `inline_sha256_matches_stored_text` (src/store/mod.rs:3048-3095) — `call_io_request` returns the exact input bytes for the `[…0xff, 0xfe…]` fixture and the sha matches the raw bytes (fails today); `just test` green.

Result: Migration 0022 adds call_io raw BLOB columns; bodies that are not valid UTF-8 are stored byte-exact and read back unchanged, so expand is lossless. Merged in #278 (f7e702d4); this entry restores the bookkeeping lost in that PR's rebase.
