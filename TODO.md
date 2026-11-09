# TODO — audit 2026-11-02 (Kotlin)

Findings from a targeted review of the Kotlin/Native port on `main` at 1d43f38. The repo has about
99k lines of non-test Kotlin in 1168 files, so this pass was not exhaustive: it covered
`rtok-sys` (file system, temp files, process spawn), the `guard` plugin's read-only classifier,
and integer narrowing in `rtok-store`. Everything here was found by reading the code; nothing was
executed against a real host. Items already tracked elsewhere are not repeated: the Linux
hardening list in [roadmap.md](roadmap.md) (P3), and the unsigned-limit fixes on the open branch
`codex/bugfix-20261102-correctness`. Once triaged, move accepted items into `plan/` as tasks.

Severity: high (data loss or security), medium (wrong result or local security), low (robustness,
testability, cleanup).

## Low

### K6. `rtok-sys` file helpers are hard to test without the real file system

- Location: `rtok-sys/src/nativeMain/kotlin/rtok/sys/Fs.kt` (top-level functions)
- What: `removeDirAll`, `createDirAll`, `tempDir` and `writeString` are top-level functions over
  libc and `getenv`, so callers (graph, memory, worktree) cannot inject a fake and their tests
  touch the real `/tmp`.
- Why: caller tests depend on the real file system, and parallel test runs can collide on temp
  names.
- Fix: put the temp and remove helpers behind a small `FileSystem` interface passed through
  `Ctx` (default = libc); give tests a per-test temp root.
