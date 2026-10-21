Confirmed by an actual-source key probe and source tracing: [cache_key's Read arm](src/plugins/guard/mod.rs#L230) stores only the trimmed input path and slice, ignoring cwd. A Read of `file.txt` in /A and one in /B produce the same key. An intervening absolute `cd /B && ls` only clears Bash keys in [post_tool](src/plugins/guard/mod.rs#L108), so it does not prevent the false Read denial. Separately, Edit/Write clears the literal `read\t{path}` prefix at line 149: an absolute edit does not invalidate a prior relative/./ spelling of the same file. T55.8 added invalidation and T322 added slices; neither establishes shared path identity.

Execution plan:

1. Add same-session fixtures for equal relative names under two cwd values and for Read("./file.txt") followed by Edit of its absolute path.
2. Reuse one path-identity function for Read keys and Edit/Write invalidation, resolving relative paths against the hook cwd without reading file bodies. Preserve meaningful filename whitespace; fail open when relative identity cannot be established.
3. Keep slice and agent/window namespaces distinct, ensure equivalent path spellings invalidate together, and retain the hook's metadata-only budget.

Check: changing cwd allows the new file's first read, editing through an alias invalidates all affected slices, unrelated files retain their cache, and `just check` passes.

Result: `path_identity()` serves both the Read key and Edit/Write invalidation, resolving relative paths against the hook cwd lexically; a relative path with no cwd or with `..` gets no key, and such an Edit clears every read key.

Check: `relative_read_is_keyed_by_its_cwd`, `edit_through_an_alias_invalidates_every_spelling`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
