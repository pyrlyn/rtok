Confirmed by the actual [paths_with_ext](src/measure/codex.rs#L28) walker: a root with real/s.jsonl returns one path; adding alias pointing to real returns two paths that canonicalize to the same file. The walker follows directory symlinks through Path::is_dir and has no visited-directory or file-identity set. [Codex requests](src/measure/codex.rs#L77), [Claude usage](src/measure/usage.rs#L115) and [stats collect](src/measure/stats.rs#L1098) parse and accumulate each returned path; a path-keyed transcript cache does not remove these aliases. Thus a single recorded request/session can contribute twice to usage and statistics. T135 addresses avoiding repeated parsing of unchanged paths, not alias deduplication.

Execution plan:

1. Add a filesystem fixture with one transcript, a directory alias and an unlinked control. Assert the discovered physical file and its usage/session contribution are counted once; include a symlink cycle and a symlink to a file where supported.
2. Define one shared traversal policy for transcript readers: resolve the configured root, then either avoid following nested symlinks or track canonical directory/file identities before traversal and emission. Keep legitimate distinct transcripts and supported linked roots readable, preserve cutoff filtering and deterministic ordering, and fail open on inaccessible entries.
3. Cover Claude/Codex usage, stats and transcript-cache callers, and consolidate the separate jsonl::parse_dir traversal so it cannot reintroduce alias double counting. Verify subagent attribution, missing targets and out-of-root links under the chosen policy.

Check: adding an alias never changes totals for the same physical transcript and cycles terminate without repeated parsing; `just check` passes. The scratch probe exercised the actual walker and proved duplicate canonical paths; downstream accumulation was source-traced, not run through a complete host parser or CLI.

Result: `paths_with_ext` in `src/measure/codex.rs` tracks canonical directory and file paths, walks real entries before links, skips dangling links and read errors, and takes a linked file's mtime from its target. `jsonl::parse_dir` now reuses `codex::jsonl_paths`, so every transcript reader shares the one walker.

Check: `measure::codex::tests::aliases_and_cycles_count_a_transcript_once` (directory alias, file alias, cycle, dangling link, symlinked root; walker, `collect`, `requests`, `parse_dir`) failed before and passes after.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
