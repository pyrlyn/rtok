// Port of crates/rtok-log/src/lib.rs — append-only file log with size rotation.
// Line format and rotation layout (`app.log`, `app.log.1`…, `app.log.rotate.lock`,
// `errors.log`) are identical to Rust rtok so both binaries can share a log dir.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package rtok.log

import kotlinx.cinterop.*
import platform.posix.*

private const val SECS_PER_DAY = 86_400L
private const val UNIX_TO_CIVIL_DAYS = 719_468L
private const val DAYS_PER_ERA = 146_097L
private val LEVELS = listOf("error", "warn", "info", "debug")

/** One rotating log file. */
data class FileLog(val path: String, val maxBytes: Long, val files: Int, val level: String)

fun now(): Long = time(null)

/** True when [level] is at or above the [floor]; unknown levels rank as `error`. */
fun enabled(floor: String, level: String): Boolean = rank(level) <= rank(floor)

private fun rank(level: String) = LEVELS.indexOfFirst { it.equals(level, ignoreCase = true) }.coerceAtLeast(0)

private fun p2(n: Long) = n.toString().padStart(2, '0')

/** `YYYY-MM-DD HH:MM:SS` UTC for unix seconds (civil-from-days). */
fun stamp(secs: Long): String {
    val days = secs / SECS_PER_DAY; val rem = secs % SECS_PER_DAY
    val z = days + UNIX_TO_CIVIL_DAYS
    val era = Math.floorDiv(z, DAYS_PER_ERA); val doe = Math.floorMod(z, DAYS_PER_ERA)
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = era * 400 + yoe + (if (m <= 2) 1 else 0)
    return "${y.toString().padStart(4, '0')}-${p2(m)}-${p2(d)} ${p2(rem / 3600)}:${p2(rem % 3600 / 60)}:${p2(rem % 60)}"
}

private object Math {
    fun floorDiv(a: Long, b: Long) = a.floorDiv(b)
    fun floorMod(a: Long, b: Long) = a.mod(b)
}

/** One log line; no field can start a second line. */
fun line(secs: Long, level: String, source: String, name: String, message: String): String {
    fun flat(s: String) = s.replace('\n', ' ').replace('\r', ' ')
    return "${stamp(secs)} ${flat(level)} ${flat(source)}/${flat(name)}: ${flat(message)}"
}

fun errorPath(logPath: String): String = logPath.substringBeforeLast('/', "").let { if (it.isEmpty() && !logPath.startsWith("/")) "errors.log" else "$it/errors.log" }

fun append(log: FileLog, level: String, source: String, name: String, message: String) =
    appendSplit(log, null, level, source, name, message)

/** Writes to the main log when [level] passes its floor, and copies `error` lines to [errors]. */
fun appendSplit(main: FileLog, errors: String?, level: String, source: String, name: String, message: String) {
    val text = line(now(), level, source, name, message)
    if (enabled(main.level, level)) runCatching { writeLine(main, text) }
    if (level.equals("error", ignoreCase = true) && errors != null)
        runCatching { writeLine(FileLog(errors, main.maxBytes, main.files, "error"), text) }
}

class LogIoException(msg: String) : Exception(msg)

fun writeLine(log: FileLog, text: String) {
    parentOf(log.path)?.let { mkdirs(it) }
    val bytes = (text + "\n").encodeToByteArray()
    if (over(log, bytes.size.toLong())) runCatching { rotateIfOver(log, bytes.size.toLong()) }
    val fd = open(log.path, O_WRONLY or O_CREAT or O_APPEND, 0x180) // 0o600
    if (fd < 0) throw LogIoException("open ${log.path}: errno $errno")
    try {
        bytes.usePinned { p -> if (write(fd, p.addressOf(0), bytes.size.convert()) < 0) throw LogIoException("write: errno $errno") }
    } finally { close(fd) }
}

fun rotatedPath(path: String, i: Int) = "$path.$i"

internal fun fileSize(path: String): Long = memScoped {
    val st = alloc<stat>(); if (stat(path, st.ptr) != 0) -1L else st.st_size
}

private fun over(log: FileLog, incoming: Long): Boolean {
    val len = fileSize(log.path).coerceAtLeast(0)
    return len > 0 && len + incoming > log.maxBytes
}

/** Rotates under a non-blocking `flock` so concurrent writers rotate at most once. */
internal fun rotateIfOver(log: FileLog, incoming: Long) {
    val fd = openRotateLock(log.path)
    try {
        if (flock(fd, LOCK_EX or LOCK_NB) != 0) throw LogIoException("rotation lock busy")
        if (over(log, incoming)) rotate(log.path, log.files)
    } finally { close(fd) }
}

/** Owner-only lock file; refuses symlinks (O_NOFOLLOW) and non-regular files (FIFOs). */
internal fun openRotateLock(path: String): Int {
    val name = "$path.rotate.lock"
    val fd = open(name, O_CREAT or O_WRONLY or O_NOFOLLOW or O_NONBLOCK, 0x180)
    if (fd < 0) throw LogIoException("open lock: errno $errno")
    val regular = memScoped { val st = alloc<stat>(); fstat(fd, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFREG }
    if (!regular) { close(fd); throw LogIoException("rotation lock is not a regular file") }
    return fd
}

private fun rotate(path: String, files: Int) {
    if (files == 0) { val fd = open(path, O_WRONLY or O_TRUNC); if (fd >= 0) close(fd); return }
    unlink(rotatedPath(path, files))
    for (i in files - 1 downTo 1) rename(rotatedPath(path, i), rotatedPath(path, i + 1))
    if (rename(path, rotatedPath(path, 1)) != 0) throw LogIoException("rename: errno $errno")
}

internal fun parentOf(path: String): String? = path.substringBeforeLast('/', "").ifEmpty { null }

internal fun mkdirs(dir: String) {
    if (dir.isEmpty()) return
    var cur = if (dir.startsWith("/")) "" else "."
    for (part in dir.split('/').filter { it.isNotEmpty() }) { cur += "/$part"; mkdir(cur, 0x1ed.convert()) }
}
