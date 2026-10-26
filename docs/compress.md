# compress plugin

Port of `src/plugins/compress` (P28). Proxy filter, default on, active only with `proxy.mode = "compress"`
and `[plugins.compress] enabled = true`. After the lossless `archive` lane has replaced an old tool result
with an `[archived …]` pointer, compress swaps that pointer (only if it equals the stored decision and the
result is shorter) for a deterministic extractive summary:

```
[compress <id8>]
type: tool_output
title: <first line, 80 chars>
narrative: <next 3 lines, 60 chars each, "; "-joined>
facts:      (≤ 3, error lines first, then "key: value" lines)
files:      (≤ 3 path-like lines)
expand(<id8>)
```

The archive row is unchanged; `expand <id>` returns the original bytes. The live zone
(`[plugins.archive] keep_turns`) is never touched.

## Model summaries (`[plugins.compress.llm]`)
With `llm.enabled = true` a stored model summary (`archive_summaries`, keyed by archive id and
`version = sha256(prompt.toml)[..12] + "+" + model`) replaces the extractive view; a missing one is offered
to the background queue. `prompt.toml` is embedded byte-for-byte (`PromptToml.kt`) so the version key matches
Rust's. `must_keep` spans (paths, identifiers, errors, numbers, commands, ids) guard a summary: one that
drops a span is rejected.

### Compressor and queue
`Compressor` (port of `llm.rs`) POSTs `{model, max_tokens, system, messages:[{role:user, content}]}` to
`[plugins.compress.llm] url` (default: the local proxy `http://<bind>:<port>`) + `/v1/messages` with
`content-type`, `x-rtok-lane: internal`, `anthropic-version: 2023-06-01` (unless forwarded) and the
agent's Anthropic credential headers (`Auth`; other providers' keys never cross). Timeouts: connect 10 s,
request/socket `timeout_s`. Outcomes: Cached, Skipped (not text, too large, diff/source, too many spans,
not shorter), Rejected (dropped a must-keep span; warned), Failed (`upstream status 503 Service
Unavailable`, `summary cut off at max_output_tokens`, transport errors; warned), Stored.
`Queue` (port of `queue.rs`) launches jobs on a supervisor coroutine scope, at most `max_in_flight`, each
id once per process (CAS reservation, T655). The proxy that calls `Queue.submit` arrives in Phase 7.

HTTP goes through rtok's own interface `libs/http` (`rtok.http.HttpClient`); `libs/http-ktor` is the Ktor
3.3.3 adapter (Darwin engine on macOS, Curl on Linux), unit-tested with `ktor-client-mock`.

## Tests
`CompressTest` 6, `MustKeepTest` 3, `LlmTest` 5, `QueueTest` 3 (queue.rs), `CompressorTest` 5; `KtorHttpTest` 4 in `:http-ktor`.
