// The real terminal: raw mode through termios, the alternate screen, the window size and key input with a timeout.
@file:OptIn(ExperimentalForeignApi::class)

package tui

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import platform.posix.BRKINT
import platform.posix.ECHO
import platform.posix.ICANON
import platform.posix.ICRNL
import platform.posix.IEXTEN
import platform.posix.INPCK
import platform.posix.ISIG
import platform.posix.ISTRIP
import platform.posix.IXON
import platform.posix.OPOST
import platform.posix.POLLIN
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.TCSAFLUSH
import platform.posix.TIOCGWINSZ
import platform.posix.fflush
import platform.posix.ioctl
import platform.posix.isatty
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.stdout
import platform.posix.tcgetattr
import platform.posix.tcsetattr
import platform.posix.termios
import platform.posix.winsize
import platform.posix.write

private const val READ_MAX = 64
private const val FALLBACK_W = 80
private const val FALLBACK_H = 24

/** Both stdin and stdout are terminals (`rtok tui` refuses otherwise). */
fun isInteractive(): Boolean = isatty(STDIN_FILENO) == 1 && isatty(STDOUT_FILENO) == 1

/** Raw mode and the alternate screen until [close]; draw with [draw], read keys with [poll]. */
class Terminal : AutoCloseable {
    private val saved = nativeHeap.alloc<termios>()
    private var raw = false
    private var last: Buffer? = null

    /** Enters raw mode, the alternate screen and hides the cursor. */
    fun open() {
        check(tcgetattr(STDIN_FILENO, saved.ptr) == 0) { "tcgetattr failed: stdin is not a terminal" }
        memScoped {
            val t = alloc<termios>()
            tcgetattr(STDIN_FILENO, t.ptr)
            t.c_iflag = (t.c_iflag.toULong() and (BRKINT or ICRNL or INPCK or ISTRIP or IXON).toULong().inv()).convert()
            t.c_oflag = (t.c_oflag.toULong() and (OPOST).toULong().inv()).convert()
            t.c_lflag = (t.c_lflag.toULong() and (ECHO or ICANON or IEXTEN or ISIG).toULong().inv()).convert()
            tcsetattr(STDIN_FILENO, TCSAFLUSH, t.ptr)
        }
        raw = true
        out("\u001b[?1049h\u001b[?25l\u001b[2J")
    }

    /** The window size in cells (80×24 when the terminal does not say). */
    fun size(): Pair<Int, Int> =
        memScoped {
            val ws = alloc<winsize>()
            if (ioctl(STDOUT_FILENO, TIOCGWINSZ.convert(), ws.ptr) == 0 && ws.ws_col.toInt() > 0) {
                ws.ws_col.toInt() to ws.ws_row.toInt()
            } else {
                FALLBACK_W to FALLBACK_H
            }
        }

    /** Draws [b], sending only the cells that changed since the last frame. */
    fun draw(b: Buffer) {
        out(b.diff(last))
        last = b
    }

    /** Forgets the last frame, so the next [draw] repaints everything. */
    fun invalidate() {
        last = null
    }

    /** Keys typed within [timeoutMs] (empty when none). */
    fun poll(timeoutMs: Int): List<Key> =
        memScoped {
            val fd = alloc<pollfd>()
            fd.fd = STDIN_FILENO
            fd.events = POLLIN.convert()
            if (poll(fd.ptr, 1.convert(), timeoutMs) <= 0) return@memScoped emptyList()
            val buf = allocArray<ByteVar>(READ_MAX)
            val n = read(STDIN_FILENO, buf, READ_MAX.convert()).toInt()
            if (n <= 0) emptyList() else decodeKeys(buf.readBytes(n))
        }

    private fun out(s: String) {
        val bytes = s.encodeToByteArray()
        var off = 0
        while (off < bytes.size) {
            val n = write(STDOUT_FILENO, bytes.refTo(off), (bytes.size - off).convert()).toInt()
            if (n <= 0) break
            off += n
        }
        fflush(stdout)
    }

    /** Restores the screen, the cursor and the saved termios; safe to call twice. */
    override fun close() {
        if (!raw) return
        out("\u001b[0m\u001b[?25h\u001b[?1049l")
        tcsetattr(STDIN_FILENO, TCSAFLUSH, saved.ptr)
        raw = false
    }
}
