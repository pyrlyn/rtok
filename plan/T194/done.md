Found 2026-09-22 in the surfaces pass: `read_frame` (`src/mcp/wrap.rs:87-124`) returns `None` on a header block without a parseable `Content-Length` and on a short body read, and both forwarding loops (:51-83) treat `None` as EOF — the pass-through pipe ends at the first broken header. The module doc (:5-7) and T59.4 promise "a malformed frame is forwarded byte-for-byte (fail open)"; the code contradicts the shipped claim. Secondary: when `Runtime::open` fails, `take_call` never runs and the `pending` map grows unbounded for the connection's lifetime.

Plan: on a broken header forward the bytes consumed so far and resynchronize at the next newline (or fall back to line framing); reserve `None` for real EOF; run `take_call` regardless of `runtime`.

Check: `tests/mcp_wrap.rs` fake-server case `content-length: 99\r\n\r\n{}` (short body) followed by a valid frame — the valid frame still reaches stdout and the malformed bytes are forwarded unchanged; `just test` green.

Do (2026-09-24): `read_frame` in `src/mcp/wrap.rs` returns `None` only at real EOF. A header block without a parseable `Content-Length`, or a body shorter than declared, comes back as `Framing::Raw` with the exact bytes read, and `write_frame` forwards them unchanged; the next call resynchronizes after the blank line. The server loop runs `take_call` whether or not the runtime opened, so `pending` no longer grows. Tests: `short_body_is_forwarded_unchanged_instead_of_ending_the_pipe` (`tests/mcp_wrap.rs`, the Check case) and `header_without_content_length_resyncs_at_the_next_frame`. `just check` green.

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
