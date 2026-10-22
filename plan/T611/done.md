GitHub's [Copilot CLI hook tutorial](https://docs.github.com/en/copilot/tutorials/copilot-cli-hooks#understand-the-pretooluse-input), checked 2026-10-11, documents `toolArgs` as a JSON string requiring parsing before accessing its fields. [HookInput::adapt_copilot](src/hooks/types.rs#L134) instead assigns that `Value` directly to `tool_input`. For `toolName: "bash"` and `toolArgs: "{\"command\":\"git status\"}"`, adaptation produces `tool_name = "Bash"` but leaves `tool_input` a string. The [main-argument reader](src/hooks/mod.rs#L138) returns `None`; similarly, a serialized `view` input with `path` gives [guard::cache_key](src/plugins/guard/mod.rs#L219) no Read key. Object-valued versions of the same fixtures work. Consequently the documented CLI payload bypasses argument-dependent duplicate-read handling and loses command/path activity detail even though the tool name was normalized correctly.

This is distinct from T262.5, which stores already adapted host input for ledger consumers: storing this adapter's output still stores a string instead of parsed arguments. The existing Copilot adapter tests use object-valued `toolArgs`, so they miss the documented CLI representation. No claim is made that all Copilot versions or transports send identical argument types.

Execution plan:

1. Add adapter regressions for JSON-string and object `toolArgs` on PreToolUse and PostToolUse. Assert equal normalized argument objects for equivalent inputs, including `command` and `path`, while existing `tool_input` retains precedence.
2. Decode serialized arguments at the host adapter boundary, reusing the existing JSON-like field handling where appropriate instead of duplicating it. Preserve object inputs, unknown fields and fail-open behavior for malformed/non-object arguments; do not replace a valid existing normalized input.
3. Add dispatch-level coverage showing that a serialized read can populate and consult the guard cache and that command/path activity summaries retain their main argument. Check that ledger storage receives the normalized object, retaining T262.5's behavior.
4. Retain malformed-string, absent-argument and already-normalized controls, cover the installed Copilot event spellings, then run focused hook/guard tests and `just check`.

Check: equivalent documented string and supported object payloads reach consumers with equivalent argument objects. The isolated executable compiled the actual HookInput struct/serde, Copilot adapter and naming helpers, main-argument reader, and Read cache-key branch. The unused Bash-only key helpers were panic-only stubs and were never called. No real Copilot process, complete hook dispatch, persistent store or end-to-end guard denial was exercised; those effects were source-traced, with integration tests prescribed above.

Result: Copilot `toolArgs` arriving as a JSON string is decoded into an object before dispatch; malformed, non-object and empty strings pass through, and an existing `tool_input` still wins.

Check: `copilot_decodes_serialized_tool_args` failed before and passes after; `hooks::` 108 green.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
