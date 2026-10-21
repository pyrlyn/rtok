**T26.1 retire what it found** · T26.0 · `src/proxy/wire.rs`, `src/proxy/anthropic.rs`, `src/proxy/openai_chat.rs`, `src/proxy/openai_responses.rs`, `.jscpd.json`
Do: the 46 clones are concentrated in the proxy (the same request/response shaping repeated across
wires) and in per-file test fixtures. Extract the proxy ones — they are the copies D6 warns about,
one shared helper at the responsible layer — and lower `threshold` to what remains.
Check: `just dup` green at the new threshold; the proxy tests are unchanged, which is what proves
the extraction did not change behaviour.
Status: done 2026-09-09 · Model: Opus 5 (subagent)
Check result: green. Three clones retired, all through `src/proxy/wire.rs`, the module's existing
home for cross-wire helpers (`str_field`, `int_field`): the `tool_results` prologue — take the array,
count the user turns, start the accumulators — became `wire::turn_setup`, used by all three wires;
`session_id` (`str_field(body, "user")`, byte-identical in both OpenAI wires) and
`provider() -> "openai"` became provided defaults on the `Wire` trait, with Anthropic the only
override. `cargo test --lib proxy` 14 passed and `cargo test --test proxy` 16 passed, both
unmodified — byte-identical passthrough, usage extraction and tool-result compression across all
three wires still hold. `threshold` 3 → 2, `minTokens` untouched; `just dup` exits 0 at 1.94 %
duplicated lines, 44 clones tree-wide.
Deviations: two. (1) The task named `src/proxy/mod.rs`; the clones it describes are in the wire
files beside it (`mod.rs`'s only flagged clone pairs with `src/web/mod.rs`), so the four sibling
files were edited instead — nothing outside `src/proxy/`. (2) The remaining
`anthropic.rs` ~ `openai_chat.rs` `tool_results` pair was left: Anthropic nests results inside a
user message's content blocks while the OpenAI wires dispatch flat on role, so unifying them needs
a walker taking the per-message match as a closure — a closure as long as the loop it replaces.
That is the case D6 calls out, where the abstraction costs more than the copy.
