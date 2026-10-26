# File log (`rtok.log`, port of `rtok-log`)

**How it works.** `append(FileLog(path, maxBytes, files, level), level, source, name, msg)` writes
`YYYY-MM-DD HH:MM:SS <level> <source>/<name>: <message>` (UTC, newlines flattened) to an owner-only
(0600) file. Before a write that would exceed `maxBytes` it rotates `app.log → app.log.1 … .N`
under a non-blocking `flock` on `app.log.rotate.lock` (opened with O_NOFOLLOW, refuses symlinks and
FIFOs). If the lock is busy or rotation fails, the line is still appended. `appendSplit` also copies
`error` lines to `errors.log`. Format and file names match Rust rtok, so both can share a log dir.
**Enable / disable.** Level floor via the `level` field (`error` < `warn` < `info` < `debug`); unknown
levels always pass. Wiring to `[log]` config arrives with config layering.
**Test.** `./gradlew :rtok-log:macosArm64Test`.
**Limitations.** POSIX only (macOS/Linux); a Windows (mingw) variant is not written yet.
