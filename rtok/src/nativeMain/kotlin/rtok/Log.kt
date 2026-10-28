// Port of src/log.rs `record`/`append`: the one funnel from a surface to the file log and the `logs` row.
package rtok

import rtok.config.Config
import rtok.config.envUserHome
import rtok.hook.expandWith
import rtok.log.FileLog
import rtok.store.Store
import rtok.store.insertLog

/**
 * `[log]` as the rotating file the log library writes. `log.path` is `~`-expanded here as well (Rust `finish`
 * semantics: `~/.rtok/…` → rtok home, `~/…` → user home), so a config that skipped `finish` (e.g. `Config()`)
 * never opens a literal `~/.rtok/logs/rtok.log` relative to the working directory.
 */
fun Config.fileLog(): FileLog = FileLog(expandWith(log.path, Config.homeDir(), envUserHome()), log.maxBytes.toLong(), log.files.toInt(), log.level)

/** Append one line to the file log; never fails upward (D1). */
fun appendLog(
    cfg: Config,
    level: String,
    source: String,
    name: String,
    message: String,
) = rtok.log.append(cfg.fileLog(), level, source, name, message)

/** The file line, then the `logs` row when `[log] to_db` and the level passes; each swallows its own errors. */
fun recordLog(
    cfg: Config,
    store: Store,
    session: String?,
    callId: Int?,
    level: String,
    source: String,
    name: String,
    message: String,
) {
    appendLog(cfg, level, source, name, message)
    if (cfg.log.toDb && rtok.log.enabled(cfg.log.level, level)) {
        runCatching { store.insertLog(level, source, name, message, session, callId, null) }
    }
}

/** log.rs `whole_lines`: the piece after the last `\n` is not a line yet (the sink's write can land in two parts). */
internal fun wholeLines(bytes: ByteArray): List<String> = bytes.decodeToString().split('\n').dropLast(1)

/** The configured `[log] lines`, as a count. */
internal fun Config.logLines(): Int = log.lines.coerceAtMost(Int.MAX_VALUE.toULong()).toInt()

/**
 * log.rs `tail`: the last [n] lines (default `[log] lines`) across the log, then `.1`, `.2`, … newest first.
 * Stops at the first rotated file that does not exist; empty when nothing was logged.
 */
fun logTail(
    cfg: Config,
    n: Int? = null,
): List<String> {
    val path = cfg.fileLog().path
    return logTailWith(wholeLines(runCatching { rtok.sys.readBytes(path) }.getOrDefault(ByteArray(0))), path, n ?: cfg.logLines())
}

/**
 * log.rs `tail_with`: [logTail]'s walk over [live], the live file's lines read by the caller. `logs watch` reads the
 * live file once for both its first screen and its follow state, so a line written as the watch starts shows once.
 */
internal fun logTailWith(
    live: List<String>,
    path: String,
    n: Int,
): List<String> {
    val out = live.asReversed().take(n).toMutableList()
    var i = 1
    while (out.size < n) {
        // Rotation is contiguous: nothing sits behind a gap.
        val text = runCatching { rtok.sys.readToString(rtok.log.rotatedPath(path, i)) }.getOrNull() ?: break
        out += rustLines(text).asReversed().take(n - out.size)
        i++
    }
    return out
}

/** Rust `str::lines`: no trailing empty line, `\r\n` endings trimmed. */
private fun rustLines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n').map { it.removeSuffix("\r") }

/** log.rs `numbered`: the tail numbered `1` newest and nothing else, what a viewer such as tailspin gets as-is. */
fun logNumbered(lines: List<String>): List<String> = lines.mapIndexed { i, line -> "${i + 1} $line" }

/**
 * log.rs `screen`: the `rtok logs` rows. Rust also colours the level here (`log_line`, owo-colors on a terminal);
 * TTY styling (ui::style) is not ported yet, so the rows are [logNumbered]'s, as Rust prints them off a terminal.
 */
fun logScreen(lines: List<String>): List<String> = logNumbered(lines)
