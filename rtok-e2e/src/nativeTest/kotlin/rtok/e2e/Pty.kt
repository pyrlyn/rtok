@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
// A pseudo-terminal for the e2e suite (the portable_pty part of tests/completions_*.rs): a command runs in the
// background with the slave as stdin/stdout/stderr, the test reads the master and types into it. No controlling
// terminal is set up; zsh's line editor and raw mode only need a tty on fd 0.

package rtok.e2e

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.O_NOCTTY
import platform.posix.O_RDWR
import platform.posix.POLLIN
import platform.posix.SIGKILL
import platform.posix.TIOCSWINSZ
import platform.posix.grantpt
import platform.posix.ioctl
import platform.posix.kill
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.posix_openpt
import platform.posix.ptsname
import platform.posix.read
import platform.posix.unlockpt
import platform.posix.usleep
import platform.posix.winsize
import platform.posix.write
import rtok.sys.pid
import rtok.sys.readToString
import rtok.sys.removeFile
import rtok.sys.tempDir
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private var ptySeq = 0
private const val BUF = 4096
private const val STEP_MS = 20
private const val SETTLE_US = 100_000u

class Pty(
    argv: List<String>,
    cwd: String,
    env: Map<String, String>,
    cols: Int = 200,
    rows: Int = 40,
) : AutoCloseable {
    private val master = posix_openpt(O_RDWR or O_NOCTTY)
    private val seen = StringBuilder()

    /** The background command's pid. */
    val child: Int

    init {
        check(master >= 0 && grantpt(master) == 0 && unlockpt(master) == 0) { "no pseudo-terminal" }
        memScoped {
            val ws = alloc<winsize>()
            ws.ws_col = cols.convert()
            ws.ws_row = rows.convert()
            ioctl(master, TIOCSWINSZ.convert(), ws.ptr)
        }
        val slave = q(ptsname(master)!!.toKString())
        val pidFile = "${tempDir()}/rtok-e2e-pty-${pid()}-${ptySeq++}.pid"
        val vars = env.entries.joinToString(" ") { (k, v) -> "$k=${q(v)}" }
        val cmd = "cd ${q(cwd)} && (env -i $vars ${argv.joinToString(" ") { q(it) }} <$slave >$slave 2>&1 & echo \$! >${q(pidFile)})"
        check(platform.posix.system(cmd) == 0) { "could not start ${argv.first()}" }
        child = readToString(pidFile).trim().toInt()
        removeFile(pidFile)
    }

    /** Everything the terminal has shown so far. */
    fun text(): String {
        drain(0)
        return seen.toString()
    }

    private fun drain(timeoutMs: Int) =
        memScoped {
            val fd = alloc<pollfd>()
            fd.fd = master
            fd.events = POLLIN.convert()
            var wait = timeoutMs
            while (poll(fd.ptr, 1.convert(), wait) > 0) {
                val buf = allocArray<ByteVar>(BUF)
                val n = read(master, buf, BUF.convert()).toInt()
                if (n <= 0) break
                seen.append(buf.readBytes(n).decodeToString())
                wait = 0
            }
        }

    /** Keys typed before a line editor or raw mode is up can be flushed, so every step waits first. */
    fun waitFor(
        text: String,
        within: Duration = 20.seconds,
    ) {
        val start = TimeSource.Monotonic.markNow()
        while (text !in seen) {
            check(start.elapsedNow() < within) { "never saw ${text.quoted()} in:\n$seen" }
            drain(STEP_MS)
        }
    }

    fun send(keys: String) {
        val bytes = keys.encodeToByteArray()
        bytes.usePinned { write(master, it.addressOf(0), bytes.size.convert()) }
    }

    /** Whether the command is still running (signal 0; the shell that started it reaped nothing). */
    fun alive(): Boolean = kill(child, 0) == 0 && !zombie()

    private fun zombie() = sh(listOf("ps", "-o", "stat=", "-p", "$child"), "/").out.trim().let { it.isEmpty() || it.startsWith("Z") }

    /** Waits for the command to exit, reading what it prints meanwhile. */
    fun waitExit(within: Duration = 20.seconds) {
        val start = TimeSource.Monotonic.markNow()
        while (alive()) {
            check(start.elapsedNow() < within) { "the command did not exit:\n$seen" }
            drain(STEP_MS)
        }
        usleep(SETTLE_US)
        drain(0)
    }

    override fun close() {
        kill(child, SIGKILL)
        platform.posix.close(master)
    }
}

private fun String.quoted() = "\"" + replace("\u001b", "\\e") + "\""
