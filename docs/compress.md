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

**Pending:** the HTTP compressor call (`llm.rs Compressor`) and `queue.rs` need an approved HTTP client /
async runtime; until then summaries are only read, never produced, by the Kotlin build.

## Tests
`CompressTest` 6, `MustKeepTest` 3, `LlmTest` 5 (4 ported + version key).
