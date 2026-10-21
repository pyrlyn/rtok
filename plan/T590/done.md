Confirmed in [resolve_with](src/plugins/read/mod.rs#L205): the request is lexically normalized before filesystem canonicalization at lines 218–224. With `root/link -> root/a/nested`, both `root/a/target.txt` and `root/target.txt` present and containing different text, native lookup of `link/../target.txt` reads `root/a/target.txt`, but rtok resolves and reads `root/target.txt`. Both targets are inside the allowed root; this is a wrong-file result, not a demonstrated root escape. Read, search and tree share this resolver. T56's Vfs/symlink-escape coverage and T430's Windows path identity do not cover parent traversal through an existing symlink.

Execution plan:

1. Add a fixture with distinct target contents and an in-root directory symlink followed by `..`; compare relative and absolute requests with native filesystem lookup. Keep a direct-path control.
2. Preserve symlink-sensitive traversal when resolving an existing request instead of collapsing parent components first. Apply the root guard to the actual resolved target and return a path naming that same target; handle missing paths explicitly without weakening confinement.
3. Cover allowed extra/dynamic roots, symlinks leaving a root, parent segments after those links, missing components and multiple links. Align the Vfs adapter with the host contract and retain Windows prefix/case coverage.
4. Add representative read/search/tree integration checks proving both selected content and root rejection, then rerun existing root-guard and path tests.

Check: existing paths identify the same file as native lookup, outside-root targets remain rejected, and `just check` passes. The Unix scratch probe compiled actual resolve/normalize functions with a standard filesystem adapter, equivalent Unix prefix checks and inactive dynamic roots; full MCP and Windows behavior were not executed.

Result: `fs::normalize_following` joins like `normalize`, but before a `..` pops it replaces the prefix built so far with its canonical path when that exists, so `link/../x` is the parent of the link's target, as a native lookup reads it. A prefix that does not exist pops lexically, so missing paths still resolve and the root guard still judges the resulting target. `resolve_with` (shared by read, search and tree, over `HostFs` and the Vfs) uses it; `normalize` itself is unchanged for `project`.

Check: `parent_segments_after_a_symlink_follow_the_target` (in-root link to `a/nested`, `target.txt` in both `a/` and the root, absolute and cwd-relative requests, a plain `..` and a direct path as controls, a name present only beside the link, and a link to a directory outside the root) failed with the old lexical collapse (read `from root`) and passes; `plugins::read` 94/94, `plugins_e2e` 22/22, whole lib 2198 passed with one failure, `agents::tests::tests_run_inside_the_host_sandbox`, which needs the nextest host-sandbox env and does not touch this code; clippy clean. Not run: Windows, and a live MCP session.

Status: done 2026-10-12
Model: Claude Code / sonnet-5.5
