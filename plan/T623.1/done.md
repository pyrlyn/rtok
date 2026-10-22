First half of T623, split when claimed (the whole task exceeds 500 lines: Rust protocol, schema, generated types, client queue, mocks and tests).

Confirmed on `2c7c40b2d8af0673f085d71d8d3e1da0051084e5` in [askDoctor and settleDoctor](web/src/api/query.tsx#L180). Pending requests are matched by answer kind and optional project key. A timeout removes the old entry, but its eventual answer still matches the next request of that kind. The client then resolves the new request with the old payload and ignores the actual new answer. Sequential server processing does not prevent this when the first operation outlasts the client timer.

A Vitest fake-clock/transport probe exercised the verbatim `createApi` implementation: plan A timed out, plan B was sent, and the late A frame resolved B with A's selection/diff. A timely-response control passed. [DoctorFix](web/src/pages/DoctorFix.tsx#L29) protects component effect generations, but cannot detect a response already assigned to the wrong API promise. This is separate from T576's server-side preview/apply selection drift and the already documented snapshot/write acknowledgement behavior.

Execution plan:

1. `src/web/protocol.rs`: a `req: Option<u64>` envelope on requests and replies (`Request`, `Reply`, flattened over `ClientMessage` and `ServerFrame`) so the committed schema and `snapshot.gen.ts` carry it; `tag_reply` writes it into an already rendered frame.
2. `src/web/mod.rs`: `inbound` reads `req` from the request and tags whatever it answers with, errors included (`message` frames), so a failed answer names its request. A request without `req` is answered as before.
3. Bless `web/src/api/ws.schema.json`, run `npm --prefix web run gen:api`; run the web gates after `git add`.
4. Tests: `protocol.rs` units (`tag_reply` equals the serialized envelope, requests with and without `req` parse) and `tests/web_e2e.rs` (a doctor plan and a refused request echo their `req` over a real socket).

Check: every answer to a request that carried `req` carries the same `req`, success or failure; a request without it is answered unchanged; `just check` passes.

Result: `src/web/protocol.rs` gains the `Request` and `Reply` envelopes (an optional `req`, flattened over `ClientMessage` and `ServerFrame`) and `tag_reply`; `inbound` in `src/web/mod.rs` reads `req` off the request and writes it into whatever answers it, `message` refusals and parse errors included. The committed schema and `snapshot.gen.ts` carry the new types. Nothing in the client sends `req` yet, so behavior is unchanged until T623.2. Tests: `protocol.rs` units (`tag_reply` equals the serialized envelope, requests parse with and without `req`) and `tests/web_e2e.rs::answers_echo_the_request_id_and_only_that_one` over a real socket; the e2e test fails with the tagging removed.

Status: done 2026-10-12 · Model: Claude Code / sonnet-5.5
