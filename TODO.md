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

## Medium

### K1. Temp files with predictable names are opened without `O_EXCL` / `O_NOFOLLOW`

- Location: `rtok-sys/src/nativeMain/kotlin/rtok/sys/Fs.kt` (`writeString` → `fopen(path, "wb")`);
  callers `rtok/plugins/memory/Extract.kt` (`extract`), `rtok/plugins/graph/Diff.kt`
  (`rtok-diff-<pid>-<hash>.txt`), `rtok/plugins/graph/Lsp.kt` (`spawn`, `rtok-lsp-<pid>.stderr`
  via `PipedChild` with `O_CREAT|O_TRUNC`), `rtok/bench/GraphSuite.kt`
- What: names are built from `tempDir()`, the pid and a counter/hash, then opened with plain
  `"wb"`/`O_TRUNC`, which follows symlinks and reuses existing files. The extractor input is the
  agent transcript, written with the default umask (usually 0644).
- Why: on Linux, where `TMPDIR` is often unset and `/tmp` is shared, another local user can
  pre-create `/tmp/rtok-lsp-<pid>.stderr` as a symlink and make rtok truncate a file the victim
  owns, or read the transcript. (macOS uses a per-user `TMPDIR`, so it is mostly safe there.)
- Fix: add `rtok.sys.createTempFile(prefix)` built on `mkstemp` (or the existing `"wbx"` path plus
  `O_NOFOLLOW` and mode 0600) and `mkdtemp` for directories; use it in the four callers and for
  the `stderrPath` in `Piped.kt`. Test: a pre-existing symlink at the target name is not followed.

### K2. `removeDirAll` runs `rm -rf` through the shell and ignores the result

- Location: `rtok-sys/src/nativeMain/kotlin/rtok/sys/Fs.kt`, `removeDirAll`
- What: `system("rm -rf '<path>'")`. Quoting is correct, but a relative path that starts with `-`
  is parsed by `rm` as options (no `--`); the exit status is dropped, so a failed removal is
  silent; and every call forks `/bin/sh` with the caller's environment. 57 call sites use it.
- Why: wrong or partial deletes are not reported; option injection for paths such as `-rf`-like
  names from user config or worktree names.
- Fix: implement the walk natively (`opendir`/`unlinkat`/`rmdir`, not following symlinks) or at
  minimum `rm -rf -- <path>` with the status checked and turned into an `IoException`. Tests: a
  path starting with `-`, a symlink inside the tree pointing outside it (target must survive), and
  a non-removable entry (error surfaces).

## Low

### K3. `createDirAll` ignores `mkdir` errors

- Location: `rtok-sys/src/nativeMain/kotlin/rtok/sys/Fs.kt`, `createDirAll`
- What: the return value of every `mkdir` is ignored, so `EACCES`, `ENOTDIR` or `ENOSPC` are
  dropped; the caller fails later with an unrelated "cannot open" error.
- Why: misleading errors (and harder tests) for a common setup failure.
- Fix: ignore only `EEXIST` when the existing entry is a directory; otherwise throw
  `IoException(ioKind(errno), "$cur: errno $errno")`.

### K4. `tempDir()` accepts an empty `TMPDIR`

- Location: `rtok-sys/src/nativeMain/kotlin/rtok/sys/Fs.kt`, `tempDir`
- What: `TMPDIR=""` (or `/`) yields `""`, so temp paths become `/rtok-…` at the file system root,
  and `Config.homeDir` falls back to `/.rtok`.
- Fix: treat an empty or non-directory `TMPDIR` as unset (`/tmp`); unit-test both.

### K5. Guard read-only classifier misses process substitution and `rg --pre`

- Location: `rtok/src/nativeMain/kotlin/rtok/plugins/guard/Guard.kt`, `readOnly`, `stemReadOnly`,
  `writerMarker`
- What: `readOnly` rejects `$(` and backticks, and `>(` is caught as a redirect, but `<(…)` is not:
  `cat <(sed -i s/a/b/ f)` is classified read-only. `rg --pre <cmd>` and
  `rg --pre=<cmd>` run an arbitrary program and are also read-only.
- Why: a writing command gets a dedup key and does not clear the `read` keys of the files it
  changed, so the next identical read can be denied as a duplicate of stale content.
- Fix: also reject `<(`; treat `rg` with `--pre`/`--pre=` (and `--search-zip` only if it is
  unwanted) as not read-only. Add both to `GuardTest.kt`.

### K6. `rtok-sys` file helpers are hard to test without the real file system

- Location: `rtok-sys/src/nativeMain/kotlin/rtok/sys/Fs.kt` (top-level functions)
- What: `removeDirAll`, `createDirAll`, `tempDir` and `writeString` are top-level functions over
  libc and `getenv`, so callers (graph, memory, worktree) cannot inject a fake and their tests
  touch the real `/tmp`.
- Why: K1–K4 cannot be regression-tested from caller tests; parallel test runs share temp names.
- Fix: put the temp and remove helpers behind a small `FileSystem` interface passed through
  `Ctx` (default = libc); give tests a per-test temp root.
