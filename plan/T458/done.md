`rtok mcp -- <server>` read an LSP-style body with `take(len).read_to_end` and no upper bound. A declared length over `rtok_hook::MAX_FRAME` (64 MiB) is now forwarded as `Framing::Raw` of the header only; the following bytes stay on the stream. A length at or under the cap still reads the body, including the short-body path.

Check: a declared length above `rtok_hook::MAX_FRAME` is `Framing::Raw` of the header only and the next frame is still read; a length at the cap still reads a short body; `just check`.

Result: `just check` against `6f8e4147`: fmt, clippy `-D warnings`, `build-min`, jscpd 1.84% (under 2%), test-changed 551 passed.
Status: done 2026-10-09
Model: Cursor / grok 4.7
