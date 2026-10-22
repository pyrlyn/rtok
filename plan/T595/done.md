Confirmed with a valid multiline literal string: `[otel] service_name` contains the lines `[proxy]` and `# port = 12`, followed by the real `[proxy]` table with a commented port default. Setting `proxy.port` to 9000 updates the requested key but also removes `# ` from the unrelated service name's payload. [reveal_commented_key](crates/rtok-config/src/validate.rs#L185) calls [reveal_assignment](crates/rtok-config/src/validate.rs#L198), whose line scan treats the payload's table/header and comment as document syntax before the actual TOML edit. The output remains valid, so validation does not detect the unintended value change. Dry-run leaves disk untouched but previews the same proposed mutation. T381's default/comment preservation tests do not cover multiline string payloads.

Execution plan:

1. Add the multiline literal fixture and an equivalent multiline basic string, with header-shaped and assignment-shaped content. Assert that setting a different key changes only that key and preserves the parsed string value and its original bytes.
2. Use TOML-aware source spans/token context for commented-default discovery, or restrict revival to comments proven to be outside string values. Share that context with live-key detection; avoid interpreting raw payload lines as table headers or assignments.
3. Cover quoted/dotted table keys, inline comments, escaped quotes, CRLF, existing live keys and batch setters. Preserve the intended uncomment-in-place behavior and trailing comment/padding guarantees.
4. Verify real and dry-run edits propose the same safe result, with dry-run writing nothing; rerun existing comment-preservation tests and `just check`.

Check: arbitrary valid TOML string content cannot be modified by setting an unrelated key. The actual setter changed the fixture service name from `[proxy]\n# port = 12\n` to `[proxy]\nport = 12\n`; the requested port and ordinary-default control both became 9000. Only scratch configuration files were used.

Result: `string_payload_lines` (toml_edit spans) marks lines inside multi-line strings; `reveal_assignment` and `live_key_present` skip them.

Check: tests over `'''` and `"""` strings, LF and CRLF.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
