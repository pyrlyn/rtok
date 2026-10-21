Second half of T623; needs T623.1. The client sends a fresh `req` with every doctor/junk/graph/diff/export/import request and resolves a pending promise only for the frame carrying its `req`.

Execution plan:

1. `web/src/api/query.tsx`: replace the kind/key match in `settleDoctor` with the `req` match, ignore replies for expired or unknown requests, fail only the request a `message` frame names (a `message` without `req` still fails every request in flight), keep a counter that survives reconnects so an old socket's late frame cannot match.
2. `web/src/api/ws.ts` `parseFrame` keeps `req`; `sample.ts`, `sampleDrill/Diff/Export.ts` and `web/e2e/rtok.spec.ts` echo it.
3. Tests: fake-clock timeout/retry with two different selections (plan A times out, plan B sent, late A frame must not resolve B), same-project graph/diff and export/import variants, concurrent requests, connection closure; focused Doctor UI tests. Run `just spa-test`, `just js`, `just spa-stories`, `just spa-e2e`.

Check: every resolved promise receives only its own request's answer, including after a timeout, and `just check` passes.

Result: `askDoctor` sends a fresh `req` (a counter that lives as long as the page, so it survives reconnects) and `settleDoctor` matches a reply by `req` and kind only; a reply without an id, with an unknown or expired id, or of another kind settles nothing. A `message` carrying `req` fails just that request (`rejectDoctor`); one without it still fails every request in flight. `parseFrame` keeps `req`, and the sample servers (`sample.ts`, `sampleDrill.ts`), the `wire` test helper (`answer`) and the e2e interceptor echo it. Tests in `query.test.ts` ("reply correlation") cover the timed-out-then-retried case, a missing id, a wrong kind, per-request refusals, reconnects and export/import in flight together; they fail on the previous matcher.
