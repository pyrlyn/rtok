**T936 `rtok worktree list` and `clean` speed** · `src/worktree/{list,clean,ops}.rs`, `src/cli/worktree.rs`
Done:
- `worktree list` (and the Worktrees dashboard page, junk) no longer walks the tagged build caches: `Row.cache_bytes` is `null` and the table prints `-` unless `rtok worktree list --sizes` is given. Source bytes are unchanged; `modified` is now the newest source file.
- `worktree clean` listed worktrees through the full `inventory` (a `git status` and a merge check per worktree) and walked them one after another; it now lists paths with `git worktree list` and walks the worktrees in parallel.
Timings on this repository (26 worktrees, 26 GB of build caches, load average ~15 from other sessions; the "before" is the installed 0.17.0-alpha.2, the "after" a debug build):

| command | before | after |
| --- | --- | --- |
| `worktree list --json` | 5.1–5.4 s | 3.5–3.7 s (`--sizes`: 5.9 s) |
| `worktree clean --idle 0d` | 13.3 s | 5.3 s |

Floor, not fixed: under 1 s is out of reach here. `list` still runs a `git status` and a `git merge-tree` per worktree for the state column (about 0.3 s each on a loaded machine, in parallel); `clean` has to walk each worktree to find its caches and sum their bytes, which is its job; `worktree add` spends its time seeding the sibling `target/` folders (`seed::from_siblings`), which T953 reworks with copy-on-write clones. The MCP `worktree_list` takes the unsized path with no way to ask for sizes yet.
Check: `list::tests::usage_sources_skips_tagged_caches`; `tests/worktree.rs::list_splits_cache_from_source_and_reports_an_orphan` (default null, `--sizes` bytes); `just check`.
