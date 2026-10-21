Source-confirmed on `0b14b88e`: [`take_call`](src/mcp/wrap.rs#L207) removes a client pending call for any server frame with the same `id`, without checking whether the frame is a response. After client `tools/call` id 7, a server `sampling/createMessage` request id 7 consumes the tool name; the eventual tool result id 7 is then forwarded without compression or its archive/measurement. Request ids in opposite directions must not be treated as one response namespace. The existing `pending_matches_tool_call_ids_only` test covers numeric versus string ids, not reverse requests.

Validation: an offline Rust harness compiled verbatim `note_call` and `take_call` from this revision. The server request returned the pending tool name; the later response returned `None`. Full transport validation remains part of the fix.

Execution plan:

1. Extend the framing fixture with an in-flight client tool call, a server request using the same numeric id, and the eventual tool response.
2. Consume pending state only for response-shaped frames; preserve server requests/notifications byte-for-byte.
3. Cover server requests with string ids, error responses and unrelated notifications; a genuine success/error response must drain its pending entry once.

Check: the server request does not remove the client call, the later large result is shortened and expandable with one measurement, and `just check` passes.

Result: `take_call` in `src/mcp/wrap.rs` ignores any frame with a `method`, so a server request or notification never drains the client's pending `tools/call`; only response-shaped frames do.

Check: `server_requests_do_not_consume_client_pending_calls` (numeric and string ids, notification, error response) failed before and passes after. No end-to-end wrapper test for the later large result.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
