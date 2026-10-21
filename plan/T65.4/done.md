From `research.md` §11. sqz's safe mode passes stack traces and secrets through whole. rtok keeps single lines matching `BUILTIN_KEEP` (`error`, `panic`, `traceback`) but the head/tail cut in `rules::apply` drops the frames under them, which is the part the model needs; secrets are deliberately not redacted (`ten_families_and_aws_key_unredacted`) and stay so.
Done when `rules::apply` detects a trace block — Python `Traceback (most recent call last):` to the next non-indented line, Rust `thread '…' panicked at` plus a following `stack backtrace:` block, JS `Error:` with `    at ` frames, Go `goroutine N [` frames, Java `Exception in thread` with `\tat` frames — and keeps the whole block in the output regardless of `head`/`tail`, only the block's own length counting against `max_lines`; a fixture per language shows the frames survive a 40-line cap; the trailer still names the archive id.

**Result (2026-09-18).** Commit `1d09962`. Trace-block detection in `rules::apply` keeps full Python/Rust/JS/Go/Java stack traces under head/tail caps; golden fixtures per language.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
