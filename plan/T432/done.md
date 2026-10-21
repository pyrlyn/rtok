The proxy forwards tool results to the model with ANSI escapes and control characters, which cost tokens and carry nothing. Done when the proxy runs T431's cleaner over the text of request messages before sending, limited to ANSI escapes, control characters and zero-width characters, byte-stable across turns so the prompt cache still hits. Harness wrappers stay: they are instructions to the model. Whitespace is decided at claim time: trailing-space or CRLF changes in a tool result can make the model's exact-match edits miss the file.

Check: a proxy test sends a tool result with escapes and asserts the upstream body has none and repeats byte for byte on the next turn.

Decision at claim: whitespace is left untouched. No trailing-space trimming, no CRLF normalisation, no blank-line collapsing, because the model's exact-match edits depend on it; `\n`, `\r` and `\t` are kept. Wrappers stay too.

Plan:
1. `src/sanitize.rs`: add `terminal_noise` (escapes, control and zero-width characters only, via the same `characters` walk with CR kept) and make the JSON string walk reusable as `strings`.
2. `src/proxy/noise.rs`: `strip` cleans every tool result and user text block through the wire's `tool_results` and `live_blobs`; assistant turns are never touched (thinking blocks are signed). Unparseable or already clean bodies keep their original bytes.
3. `src/proxy/mod.rs`: call it in `shape_request` in `compress` mode only, like the other rewrites; passthrough and plain stay byte-identical. No new config key.
4. Tests: cleaner unit tests, `noise.rs` unit tests, and a proxy test that sends escapes twice and matches the exact upstream body both times.
5. Verify with `just check`, then close the task.

Result: `proxy::noise::strip` cleans every tool result and user text block of the outgoing request through `sanitize::terminal_noise` (escapes, control and zero-width characters only; whitespace, CR and wrappers stay), in `proxy.mode = "compress"` only, with no new config key; assistant turns are never touched, and an unparseable or clean body keeps its original bytes. Tests: cleaner unit cases, `proxy::noise` unit tests, and `compress_mode_strips_terminal_noise_byte_stably`, which matches the exact upstream body on two identical turns.
