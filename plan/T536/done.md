Source-confirmed on `0b14b88e`: [`emit_filtered_to`](src/plugins/cmd/run.rs#L323) creates a lossy UTF-8 view before opening the runtime. Both the runtime-open failure at line 332 and the archive-write failure at line 393 print that lossy view. With a body containing byte `0xff` and an unavailable database/archive, the fallback outputs UTF-8 replacement bytes instead; no usable archive can restore the input. The normal successful archive path already preserves binary bytes, so its tests do not cover this fallback.

Execution plan:

1. Inject runtime-open failure and archive-write failure using a temporary store/path and non-UTF-8 input. Compare output bytes, not decoded strings.
2. Share a raw-byte fallback writer for both failures, preserving the CLI's established trailing-newline convention and wrapped exit code.
3. Keep the successful formatter/archive path and tiny-body behavior unchanged; verify no dead archive pointer is emitted on failure.

Check: the original bytes survive both failure branches, the command status is preserved, and `just check` passes.

Result: `emit_filtered_to` has one `write_raw` closure that writes the command's own bytes plus the usual trailing newline. The runtime-open failure, the archive-write failure and the tiny-body branch all use it; the last used to carry a copy of the same lines.

Check: `raw_bytes_survive_a_failing_store` (store path under a regular file; archive dir a regular file; body with `0xff` bytes and no final newline) compares printed bytes and fails with the fallback writing the lossy view in both branches, passes with `write_raw`; `plugins::cmd` 169/169; clippy clean.

Status: done 2026-10-12
Model: Claude Code / sonnet-5.5
