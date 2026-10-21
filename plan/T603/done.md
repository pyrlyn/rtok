Source audit only; no product code changed. Followed the JSON-tree reference/template pipeline and MCP fold wrapper against T475's losslessness and line-limit requirements, after T554's broader folding/rendering pass.

Execution plan:

1. Trace JSON value tallying, variable id assignment, `insert_field`, template generation and rendering; compare folded output with the input types.
2. Trace MCP fold line checking through pointer assembly and compare it with normal line-cut behavior.
3. Probe both candidates using actual folding and wrapper functions with isolated fixtures and controls; deduplicate against T475/T554.
4. Record only confirmed contract violations; document tested boundaries when a candidate is not established.

Result: filed T604 for literal JSON strings that equal generated variable references and therefore share an element template with object-valued occurrences. The separate max-lines candidate was not filed: the isolated probe's wrapper appended a pointer beyond the folded text's line count, but ordinary MCP line-cut output also appends a pointer after applying its content cap. No independent total-response cap contract violation was established. T475's `max_lines` coverage and T554's broad fold inspection are related but do not cover the reference/string collision.

Evidence revision: `e06ada0852e3cc28e5dcaa04d26faa6bdc2bc5ab`; merged main `085b49e9450de9326e7738dc441718e1bd667363` did not change the candidate source files. The offline scratch executable compiled extracted production folding/template/render/hash functions, actual TOON eligibility, and the actual MCP fold wrapper using minimal config, recording and length-estimation adapters. Two equal object values and a literal equal to their generated `V-<sha1-8>` id were all rendered under one `EL-` template; an ordinary-string control templated only the object values. At `max_lines = 8`, the wrapper emitted nine lines; smaller-cap and `read` controls skipped folding. The collision fixture output was 1,075 bytes vs 1,932 original bytes. No full CLI/proxy transport, archive storage, production tokenizer or real agent was exercised.

Validation: `CHECK_BASE=085b49e9450de9326e7738dc441718e1bd667363 mise exec -- just check` reported no code changes against merged main and skipped Cargo tests for this documentation-only diff; `git diff --check` passed. Active plan cards and `todo.md` contain the same 116 unique task IDs.

Status: done 2026-10-11
Model: OpenAI / GPT-6
