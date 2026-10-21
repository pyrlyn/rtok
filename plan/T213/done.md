Found 2026-09-22 in the surfaces pass: `initialize` (`src/mcp.rs:208-231`) discards `params.protocolVersion` and returns whatever `ServerInfo` serializes — no negotiation, and no test pins `result.protocolVersion`, so a dependency bump can silently change the advertised dialect (`src/doctor.rs:862` probes `2024-11-05` while tests send `2025-06-18`). `-32601` carries the raw method name as `message` instead of "Method not found". And `tools/call` coerces instead of validating: `mem_save` without `body` stores an empty note (`unwrap_or("")`, :332-339), a missing `expand` `id` becomes "unknown archive id: ", `handoff` truncates `budget_tokens` u64→u32 (:438-444) — schema-vs-handler drift turning client bugs into corrupt data.

Plan: return the client's `protocolVersion` when supported (else a pinned constant) and pin it in tests; `message: "Method not found"`; one `require_str`/`require_int` helper per handler enforcing each schema's `required` list before any store write, mapped to `-32602` in `call_tool`.

Check: `initialize_names_the_server_rtok` asserts the pinned `result.protocolVersion`; `batch_answers_with_an_array` asserts "Method not found"; `mem_save` with `{"title":"t"}` returns `isError` "invalid params: missing `body`" and the notes table stays empty; `just test` green.

Result: initialize negotiates protocolVersion (echo a supported client version, else pinned 2025-06-18); -32601 says "Method not found" with the method in data; tools/call (server and rtok mcp call) rejects a missing/empty required field from the tool's own input_schema with isError "invalid params: missing `x`" before invoke; mem_save's schema no longer requires kind (handler defaults it); handoff saturates budget_tokens instead of wrapping u64→u32.

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
