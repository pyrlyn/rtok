Confirmed on `e06ada0852e3cc28e5dcaa04d26faa6bdc2bc5ab` with the actual folding functions. In a root object's `children`, give nodes `a` and `b` the same object-valued `value`, then give node `literal` a string equal to that object's generated `V-<sha1-8>` identifier. [insert_field](src/plugins/json_tree/mod.rs#L323) represents a hoisted object as `Value::String(id)`, exactly like the original literal. [collect_bodies](src/plugins/json_tree/mod.rs#L361) and [stamp](src/plugins/json_tree/mod.rs#L372) consequently assign all three nodes the same element template. The probe produced `EL-a5ce585c: {"type":"ITEM","value":"V-65c37664"}` for all three, although the third input value is a string and the other two are objects. Changing the literal to `ordinary-literal` leaves only the two object-valued nodes templated.

This is a collision between user data and the encoder's reference representation, not a SHA-1 hash collision. The folded preview incorrectly conflates values and cannot tell a reader which occurrence is literal. The archived original remains recoverable; no irreversible archive loss is claimed. T475 implements hoisting and T554 inspected general folding behavior, but neither covers this case. T532's typed cache-key collision concerns a separate mechanism.

Execution plan:

1. Add a deterministic regression using two identical object-valued fields and one literal equal to their generated variable id. Assert that the literal retains its string meaning and is not assigned the same element body as the references. Retain the ordinary-literal control and insertion-order determinism checks.
2. Keep generated references distinct from JSON strings in the internal folded representation, including the body identity used for template counting and stamping. Do not infer a reference solely from a string's spelling; preserve the source JSON value until the encoder explicitly replaces it with a typed reference.
3. Render an unambiguous distinction between a reference and literal reference-like text in both inline fields and `ELEMENTS` bodies. Make variable-use collection follow actual references, covering nested user strings, identity extras and reserved-looking literals without treating them as encoder metadata.
4. Exercise proxy and MCP folding with the collision fixture, including their size gates and Measurements. Verify that folding retains the distinction, stored-pointer replay is stable, and `expand` still returns the exact original bytes. Run focused JSON-tree/MCP tests and `just check`.

Check: the reproduced object/string fields remain distinguishable in the folded output while repeated object bodies still share templates. The offline probe compiled extracted production fold/render/hash functions and the actual TOON eligibility helper. It also executed the extracted MCP fold wrapper with a minimal enabled config and recording/estimation adapters: the collision fixture was accepted at 1,075 output bytes versus 1,932 original bytes. Full CLI/proxy transport, archive storage and a production tokenizer were not executed.

Result: JSON-tree node bodies store each field as a hoisted reference or the source value; template keys use `canonical_body` and `referenced_vars` follows real references only (`mark_ids` removed). A reference prints bare; an id-shaped literal string is quoted.

Check: `a_literal_equal_to_a_var_id_stays_distinct_from_the_reference`, `a_reference_shaped_literal_does_not_mark_a_var_used`, `the_proxy_folds_the_collision_and_expand_returns_the_original`, `mcp_fold_keeps_a_reference_shaped_literal_distinct`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
