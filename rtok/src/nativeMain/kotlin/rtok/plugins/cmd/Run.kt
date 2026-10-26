// Port of src/plugins/cmd/run.rs: `rtok run -- <cmd>` runs the command in a shell, captures its output
// and prints the filtered form (or the raw bytes whenever filtering would not help).
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.plugins.cmd

import kotlinx.cinterop.*
import rtok.cmdStem
import rtok.config.Config
import rtok.config.envVar
import rtok.plugin.Runtime
import rtok.plugin.identicalResult
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Measurement
import rtok.shortId
import rtok.store.Sanitize
import rtok.store.hexSha256
import rtok.sys.ProgressRunner
import rtok.sys.exitCode
import rtok.sys.rustLines

/** POSIX single-quote [s]; an embedded `'` becomes `'"'"'`. */
fun shQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

/** Quote a command for the PreToolUse rewrite `rtok run -- <quoted>` (PowerShell `''` on Windows). */
fun wrapQuote(
    s: String,
    windows: Boolean = false,
): String = if (windows) psQuote(s) else shQuote(s)

private fun psQuote(s: String) = "'" + s.replace("'", "''") + "'"

/** cmd.exe quoting: bare when safe, else `"…"` with `"` doubled. */
fun cmdQuote(s: String): String =
    when {
        s.isEmpty() -> "\"\""
        s.none { it.isWhitespace() || it in "\"^&|<>%" } -> s
        else -> "\"" + s.replace("\"", "\"\"") + "\""
    }

enum class ShellKind { Posix, Cmd, PowerShell }

fun shellKind(shell: String): ShellKind =
    when (cmdStem(shell).lowercase()) {
        "cmd" -> ShellKind.Cmd
        "powershell", "pwsh" -> ShellKind.PowerShell
        else -> ShellKind.Posix
    }

private val POSIX_SHELLS = listOf("sh", "bash", "zsh", "dash", "ksh", "ash")

/** One arg is a shell snippet as written; several are quoted words. Both merge stderr into stdout. */
fun scriptFor(
    kind: ShellKind,
    args: List<String>,
): String {
    fun inner(q: (String) -> String) = args.singleOrNull() ?: args.joinToString(" ", transform = q)
    return when (kind) {
        ShellKind.Posix -> "{ ${inner(::shQuote)}\n} 2>&1"
        ShellKind.Cmd -> "(${inner(::cmdQuote)}) 2>&1"
        ShellKind.PowerShell -> "& { ${inner(::psQuote)} } *>&1"
    }
}

/** `[plugins.cmd].shell`, else a POSIX `$SHELL`, else the host default. */
fun resolveShell(
    cfgShell: String,
    envShell: String?,
    windows: Boolean,
    comspec: String?,
): String =
    when {
        cfgShell.isNotEmpty() -> cfgShell
        envShell != null && POSIX_SHELLS.any { cmdStem(envShell).equals(it, ignoreCase = true) } -> envShell
        windows -> comspec?.takeIf { it.isNotEmpty() } ?: "cmd.exe"
        else -> "/bin/sh"
    }

internal fun shell(cfg: Config) = resolveShell(cfg.plugins.cmd.shell, envVar("SHELL"), false, envVar("ComSpec"))

fun shellArgs(
    shell: String,
    body: String,
): List<String> =
    when (shellKind(shell)) {
        ShellKind.Cmd -> listOf("/D", "/C", body)
        ShellKind.PowerShell -> listOf("-NoProfile", "-NonInteractive", "-Command", body)
        ShellKind.Posix -> listOf("-c", body)
    }

/** The spinner text: first line of the command, at most 60 characters. */
internal fun runningLabel(args: List<String>): String {
    val first =
        args
            .joinToString(" ")
            .rustLines()
            .firstOrNull()
            .orEmpty()
    return "running ${first.take60()}…"
}

private fun String.take60(): String {
    var n = 0
    var i = 0
    while (i < length && n < 60) {
        i += if (this[i].isHighSurrogate()) 2 else 1
        n++
    }
    return substring(0, minOf(i, length))
}

/**
 * Whether the shown output needs the `[rtok <id> · N lines]` pointer: always once it is long,
 * never when only whitespace/ANSI changed, otherwise unless the filter already named the archive.
 */
fun needsPointer(
    lines: UInt,
    trailerMinLines: UInt,
    body: ByteArray,
    filtered: String,
    named: Boolean,
): Boolean =
    when {
        lines > trailerMinLines -> true
        canonical(body).contentEquals(canonical(filtered.encodeToByteArray())) -> false
        else -> !named
    }

fun namesTheId(
    filtered: String,
    id: String,
) = filtered.contains("expand $id")

fun trailer(
    id: String,
    lines: UInt,
) = "[rtok ${shortId(id)} · $lines lines]"

/** Bytes with ANSI escapes dropped, blank runs collapsed to one space, blank lines and edges trimmed. */
private fun canonical(body: ByteArray): ByteArray {
    // One char per byte, so the shared escape scanner walks byte offsets.
    val s = CharArray(body.size) { (body[it].toInt() and 0xff).toChar() }.concatToString()
    val out = ArrayList<Byte>(body.size)
    val line = ArrayList<Byte>()

    fun flush() {
        var a = 0
        var b = line.size
        while (a < b && line[a].isAsciiSpace()) a++
        while (b > a && line[b - 1].isAsciiSpace()) b--
        if (a == b) return
        if (out.isNotEmpty()) out += '\n'.code.toByte()
        for (k in a until b) out += line[k]
        line.clear()
    }
    var i = 0
    while (i < body.size) {
        when (val c = body[i].toInt() and 0xff) {
            0x1b -> {
                i = Sanitize.skipEscape(s, i)
            }

            '\n'.code -> {
                flush()
                line.clear()
                i++
            }

            ' '.code, '\t'.code, '\r'.code -> {
                while (i < body.size && (body[i].toInt() and 0xff).let { it == 0x20 || it == 9 || it == 13 }) i++
                if (line.isNotEmpty()) line += ' '.code.toByte()
            }

            else -> {
                line += c.toByte()
                i++
            }
        }
    }
    flush()
    return out.toByteArray()
}

private fun Byte.isAsciiSpace() = toInt().let { it == 0x20 || it == 9 || it == 10 || it == 12 || it == 13 }

internal const val CAPTURE_CAP = 32L * 1024 * 1024

/** stdout then stderr, plus a notice when the cap dropped bytes. */
internal fun captureBody(
    cmd: ProgressRunner,
    cap: Long,
): Pair<ByteArray, Int> {
    val (out, dropped) = cmd.runCapped(cap)
    var body = out.stdout + out.stderr
    if (dropped > 0) {
        if (body.isNotEmpty() && body.last() != '\n'.code.toByte()) body += '\n'.code.toByte()
        val shown = if (cap % (1024 * 1024) == 0L) "${cap / (1024 * 1024)} MiB" else "$cap bytes"
        body += "[rtok: output capped at $shown; $dropped bytes not captured]\n".encodeToByteArray()
    }
    return body to exitCode(out)
}

/** `rtok run`: the command's exit code, its output filtered onto stdout. */
fun run(
    cfg: Config,
    args: List<String>,
    agent: String?,
): Int {
    require(args.isNotEmpty()) { "rtok run: missing command" }
    val sh = shell(cfg)
    val cmd = ProgressRunner(runningLabel(args), sh, shellArgs(sh, scriptFor(shellKind(sh), args)))
    val (body, code) = captureBody(cmd, CAPTURE_CAP)
    emitFiltered(cfg, args, body, code, agent)
    return code
}

/** `rtok expand` output is the archive being read back; filtering or deduping it would defeat it. */
internal fun runsRtokExpand(argv: List<String>): Boolean {
    val snippet = if (argv.size == 1) argv[0] else argv.joinToString(" ", transform = ::shQuote)
    return Bounded.stages(snippet).any { stage ->
        val words = stage.dropWhile { '=' in it }
        words.firstOrNull()?.let { cmdStem(it).equals("rtok", ignoreCase = true) } == true && words.getOrNull(1) == "expand"
    }
}

fun emitFiltered(
    cfg: Config,
    argv: List<String>,
    body: ByteArray,
    exit: Int,
    agent: String?,
) = emitFilteredTo(cfg, argv, body, exit, agent, ::writeStdout)

private fun writeStdout(b: ByteArray) {
    if (b.isNotEmpty()) b.usePinned { platform.posix.fwrite(it.addressOf(0), 1u, b.size.convert(), platform.posix.stdout) }
}

private val NL = '\n'.code.toByte()

internal fun emitFilteredTo(
    cfg: Config,
    argv: List<String>,
    body: ByteArray,
    exit: Int,
    agent: String?,
    out: (ByteArray) -> Unit,
) {
    val before = body.decodeToString()

    fun writeText(t: String) {
        out(t.encodeToByteArray())
        if (t.isNotEmpty() && !t.endsWith('\n')) out(byteArrayOf(NL))
    }

    fun writeRaw() {
        out(body)
        if (body.isNotEmpty() && body.last() != NL) out(byteArrayOf(NL))
    }
    if (runsRtokExpand(argv)) return writeRaw()
    val cx = runCatching { Runtime.open(cfg, "run") }.getOrElse { return writeRaw() }
    cx.use {
        identicalResult(cx, "cmd", body, agent)?.let { return writeText(it) }
        // The `cat <file>` re-read delta (Rust `catfile::reread_key` + `read::cache`) joins with the read plugin.
        val id = hexSha256(body)
        val lines = if (body.isEmpty()) 0u else before.rustLines().size.toUInt()
        val pointerLine = trailer(id, lines)

        fun raw() {
            writeRaw()
            val est = cx.estimate(Bounded.hostVisiblePrefix(before), Class.Code)
            runCatching { cx.record(Measurement("cmd", "raw", body.size.toULong(), body.size.toULong(), est, est)) }
        }
        if (body.size <= pointerLine.byteLen) return raw()
        val settings = Settings.fromConfig(cfg)
        val family = Formatters.familyOf(settings, argv, before)
        val (filtered, kind) = Formatters.compress(settings, argv, before, exit, shortId(id))
        val named = namesTheId(filtered, shortId(id))
        val c = cfg.plugins.cmd
        val pointer = c.trailer && needsPointer(lines, c.trailerMinLines, body, filtered, named)
        val noShorter = filtered.trimEnd().byteLen >= before.trimEnd().byteLen
        val shown =
            buildString {
                append(filtered)
                if (isNotEmpty() && !endsWith('\n')) append('\n')
                if (pointer) append(pointerLine).append('\n')
            }
        val validUtf8 = runCatching { body.decodeToString(throwOnInvalidSequence = true) }.isSuccess
        if (validUtf8 && ((lines <= c.trailerMinLines && noShorter) || shown.byteLen >= body.size)) return raw()
        if ((pointer || named) && runCatching { cx.putArchiveFor(body, agent) }.isFailure) return writeRaw()
        val bytes = shown.encodeToByteArray()
        out(bytes)
        runCatching {
            cx.record(
                Measurement(
                    "cmd",
                    kind,
                    body.size.toULong(),
                    bytes.size.toULong(),
                    cx.estimate(Bounded.hostVisiblePrefix(before), Class.Code),
                    cx.estimate(Bounded.hostVisiblePrefix(shown), Class.Code),
                    if (pointer || named) "$family:$id" else null,
                ),
            )
        }
    }
}
