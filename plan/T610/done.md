Source audit only; no product code changed. Traced shared `cmd_stem` and `canonical_tool_name` mappings, their hook/guard/activity consumers, and Copilot argument adaptation. This sampled pass does not claim exhaustive host-schema or all-caller coverage.

Execution plan:

1. Trace shared name helpers from host adapters to their consumers and compare host-specific input shapes with the retained normalized fields.
2. Probe boundary inputs with object/string and malformed controls, and inspect Copilot range arguments only where a local schema establishes applicability.
3. Deduplicate against existing task cards before filing; document unresolved candidates and coverage limits.

Result: filed T611 for the documented Copilot CLI representation of `toolArgs` as a JSON string. `HookInput::adapt_copilot` retains that string in `tool_input`, so consumers that read object fields do not see `command` or `path`. T262.5 fixed storing adapted input for ledger consumers but did not parse this representation; existing adapter tests use object-valued `toolArgs`. No separate `view_range` finding was filed because no local schema or runtime fixture established that argument's applicability. Other helper consumers were source-inspected only; this is not an exhaustive audit of `names.rs`.

Evidence revision: `08f1df909f39f85079ef6e74454a438ef04ea398`; fetched main was `085b49e9450de9326e7738dc441718e1bd667363`. GitHub's [Copilot CLI hook tutorial](https://docs.github.com/en/copilot/tutorials/copilot-cli-hooks#understand-the-pretooluse-input), checked 2026-10-11, documents the string form. The offline scratch executable compiled the actual HookInput serde/adaptation, shared naming helpers, main-argument reader and Read cache-key branch. Object inputs produced `main_arg=Some(...)` and a Read cache key; equivalent serialized inputs left `tool_input` as a string, yielded no main argument and no Read cache key. Malformed strings, normalized inputs and Unicode command basenames passed controls. The Bash-only key helpers were panic-only stubs and not called. No live Copilot, full hook dispatch, persistent store or end-to-end guard denial was exercised.

Validation: the isolated probe passed; `git diff --check` passed. The scoped `just check` against `085b49e9` reported no code changes and skipped Cargo tests for the docs-only diff. Active plan cards and `todo.md` contain the same 120 unique IDs.

Status: done 2026-10-11
Model: OpenAI / GPT-6
