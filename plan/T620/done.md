Confirmed on `2c7c40b2d8af0673f085d71d8d3e1da0051084e5` in [Session::request](src/plugins/graph/lsp.rs#L461). Its deadline is checked before `read_msg`, but that function performs blocking pipe reads without a deadline. A matching response returns immediately without checking the elapsed deadline. An isolated probe compiled the verbatim request/framing methods against a synthetic subprocess peer: with `ready()` set to 10 ms and the peer delaying its reply by 250 ms, the request still succeeded after 675 ms wall time. A peer that stays silent can leave the call blocked instead of reaching the documented fallback.

[with_session](src/plugins/graph/lsp.rs#L544) holds the shared session mutex through the call; [capability::ask](src/plugins/graph/capability.rs#L197) invokes the server closure synchronously. T376 adds fallback after a returned error, but that does not enforce a deadline on an unread pipe.

Execution plan:

1. Add deterministic fake-peer regressions for no reply, a delayed reply, a partial header/body and intervening notifications. Assert bounded completion and timeout classification, with a timely-response control.
2. Make framing reads deadline-aware using one transport owner and bounded cancellation. Include writes in the request budget so a peer that stops consuming input cannot bypass the same bound.
3. On timeout, invalidate or safely resynchronize the session, release locks and reach the existing fallback path. Verify a later request and shutdown cannot be trapped behind the expired request or consume its late response.
4. Run focused LSP transport, capability/fallback and shutdown tests, then `just check`.

Claimed design (single PR, well under 500 code lines): `Session` owns a reader thread (framing into an mpsc channel) and a writer thread (a channel into stdin), so `request` waits with `recv_timeout` on the remaining budget and a peer that neither answers nor consumes input costs at most the budget. On expiry the server is killed and reaped, so `with_session` respawns it and a late reply can never be matched to a later request; `Drop` sends `exit` through the writer channel and cannot block. Tests in `lsp.rs` use `sh` peers with bounded lifetimes, first run against the old blocking reads to prove they fail.

Check: an unresponsive peer cannot hold an LSP request beyond its configured budget plus a bounded cleanup allowance. Executed evidence used extracted production request/framing code, a synthetic peer and a 10 ms timeout shim; real language servers, the full capability dispatcher and silent-peer indefinite waiting were not executed.

Result: `Session` in `src/plugins/graph/lsp.rs` gives its server pipes to a reader thread and a writer thread; `request_within` waits on the reader's channel with `recv_timeout` on the remaining budget, so a silent peer, a late or partial reply, or a peer that stops reading input ends in `lsp: timeout waiting for <method>` (a `server_broke` error, so the existing tags fallback runs) within the budget. Expiry kills and reaps the server, so `with_session` respawns it and a late reply is never matched to a later request; `Drop` queues `exit` and cannot block. Fake `sh` peers cover no reply, a reply after the budget, a partial body, a header without a body, notifications, a peer that never reads (1 MiB request, and a drop with a full pipe), the expired session and timely controls; seven failed against the old blocking reads and passed after the change; `just check`.
Status: done 2026-10-12

Model: Claude Code / claude-sonnet-5-5
