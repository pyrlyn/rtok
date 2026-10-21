T672 prints 8 hex chars on a compressed command. A `toon` block and a `json_tree` fold still named the archive with the full 64-character sha (`[toon <sha>]`, `[json-tree <sha>]`). That text is what an agent reads back.

Both pointers now print the 8-character prefix, and the size check prices a pointer of that length. `rtok expand` resolves the prefix. The measurement `ref_id` stays the full sha. A decision already stored is replayed as written, so an old pointer is not rewritten.

Check: the toon expand test and the json_tree fold test find the 8-character prefix, not the sha, and `expand` of that prefix returns the original bytes. `proxy_session_saves_over_the_floor` matches that prefix in the body upstream received.

Status: done 2026-10-20
