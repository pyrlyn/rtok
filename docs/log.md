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

## Reading it: `rtok logs`, `rtok logs export`, `rtok logs watch`, `rtok info`

**How it works.** `rtok logs` prints the last `[log] lines` (200) lines, newest first and numbered `1` newest,
walking `rtok.log`, then `.1`, `.2`, … (`logTail`, `logScreen`). `export` prints the same selection without numbers;
`--json` prints it as a JSON array; `--lines N` (global) overrides the count. `watch` prints that screen, then
follows the live file (`Follow`): every new line lands above the old ones. A terminal is repainted in place (cursor
up, clear below; no raw mode); a pipe gets the new rows appended, numbered past the first screen. `Follow` keeps the
file it is reading open (rtok-sys `FileHandle`, device + inode), so a rotation is told by file identity: the unread
tail of the old file still comes off the open handle, and generations that rotated past between two polls are read
in order. The watch ends when its parent goes away (reparented) or its reader closes the pipe.
`[log] tspin = "auto"` (`always`, `off`) pipes the numbered rows through `tspin --print` (tailspin) when stdout is a
terminal (`always`: regardless); without `tspin` on PATH the rows print plain. `rtok info` counts the log's bytes
(live file and rotated siblings), lines, and `error` lines (the third field of a line is its level).
**Enable / disable.** Always available; `RTOK_LOG_TSPIN=off` (or `[log] tspin = "off"`) keeps tailspin out.
**Test.** `LogFollowTest` (tail, screen, follow across rotation, deletion, truncation and a rotation storm),
`InfoTest`, `LogsCliSpec` (`./gradlew cliTest`), `LogsE2eSpec` (watch against a real process, fake tailspin,
orphaned watch; `./gradlew e2eTest`).
**Limitations.** No level colours yet (`ui::style` is not ported): output matches Rust's off a terminal. `rtok info`
has no spinner. When one poll returns several lines, the terminal window shows the newest on top; Rust's put the
oldest of them on top.
