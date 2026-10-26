// Port of the capture half of rtok-sys `proc` (`ProgressRunner::run_capped`, `exit_code`): spawn a program
// with piped stdout/stderr, keep at most `cap` bytes, and show a stderr spinner while it runs.
@file:OptIn(ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.*
import platform.posix.*
import rtok.sys.spawn.*

/** What a finished child printed and how it ended. [exitCode] is null when a signal killed it. */
class Captured(val stdout: ByteArray, val stderr: ByteArray, val exitCode: Int?)

/** Runs [program] with [args] (no shell) and captures its output; [label] is the spinner text. */
class ProgressRunner(private val label: String, private val program: String, private val args: List<String>) {
    /** Captures up to [cap] bytes over both streams; the second value counts the bytes dropped past it. */
    fun runCapped(cap: Long): Pair<Captured, Long> = memScoped {
        val outPipe = allocArray<IntVar>(2); val errPipe = allocArray<IntVar>(2)
        check(pipe(outPipe) == 0 && pipe(errPipe) == 0) { "pipe: errno $errno" }
        val actions = alloc<posix_spawn_file_actions_tVar>()
        posix_spawn_file_actions_init(actions.ptr)
        posix_spawn_file_actions_adddup2(actions.ptr, outPipe[1], 1)
        posix_spawn_file_actions_adddup2(actions.ptr, errPipe[1], 2)
        for (fd in listOf(outPipe[0], outPipe[1], errPipe[0], errPipe[1])) posix_spawn_file_actions_addclose(actions.ptr, fd)
        val argv = allocArrayOf((listOf(program) + args).map { it.cstr.ptr } + listOf(null))
        val pid = alloc<pid_tVar>()
        val rc = posix_spawnp(pid.ptr, program, actions.ptr, null, argv, environPtr())
        posix_spawn_file_actions_destroy(actions.ptr)
        close(outPipe[1]); close(errPipe[1])
        if (rc != 0) { close(outPipe[0]); close(errPipe[0]); throw IoException(if (rc == ENOENT) IoException.Kind.NotFound else IoException.Kind.Other, "$program: spawn errno $rc") }
        val sinks = listOf(Sink(outPipe[0]), Sink(errPipe[0]))
        val spinner = Spinner.pick(label)
        var budget = cap; var dropped = 0L
        val fds = allocArray<pollfd>(2); val buf = ByteArray(64 * 1024)
        while (sinks.any { it.open }) {
            sinks.forEachIndexed { i, s -> fds[i].fd = if (s.open) s.fd else -1; fds[i].events = POLLIN.toShort(); fds[i].revents = 0 }
            if (poll(fds, 2u, Spinner.TICK_MS) == 0) { spinner.tick(); continue }
            sinks.forEachIndexed { i, s ->
                if (!s.open || fds[i].revents.toInt() == 0) return@forEachIndexed
                val n = buf.usePinned { read(s.fd, it.addressOf(0), buf.size.convert()) }.toInt()
                if (n <= 0) { if (n < 0 && errno == EINTR) return@forEachIndexed; s.open = false; close(s.fd); return@forEachIndexed }
                val keep = minOf(n.toLong(), budget).toInt()
                if (keep > 0) s.bytes.write(buf, keep)
                budget -= keep; dropped += n - keep
            }
        }
        spinner.finish()
        Captured(sinks[0].bytes.toByteArray(), sinks[1].bytes.toByteArray(), waitExit(pid.value)) to dropped
    }

    private class Sink(val fd: Int) { var open = true; val bytes = Bytes() }

    private fun waitExit(pid: pid_t): Int? = memScoped {
        val status = alloc<IntVar>()
        while (waitpid(pid, status.ptr, 0) < 0) if (errno != EINTR) return null
        val s = status.value
        // WIFEXITED / WEXITSTATUS, spelled out because the macros are not exported to Kotlin.
        if (s and 0x7f == 0) (s shr 8) and 0xff else null
    }
}

/** Growable byte buffer (Kotlin/Native has no `ByteArrayOutputStream`). */
internal class Bytes {
    private var a = ByteArray(4096); private var n = 0
    fun write(b: ByteArray, len: Int) {
        if (n + len > a.size) a = a.copyOf(maxOf(a.size * 2, n + len))
        b.copyInto(a, n, 0, len); n += len
    }
    fun toByteArray() = a.copyOf(n)
}

private fun environPtr() = rtok_environ()

/** Like Rust `exit_code`: the code, or 1 when a signal ended the child. */
fun exitCode(c: Captured): Int = c.exitCode ?: 1

/**
 * The stderr spinner. Shown only when stderr is a terminal and `RTOK_NO_PROGRESS` is not `1`
 * (Rust `indicator::shown`); otherwise every call is a no-op.
 */
internal class Spinner private constructor(private val label: String?) {
    private var frame = 0; private var drawn = false
    fun tick() { val l = label ?: return; drawn = true; eprint("\r\u001b[2K\u001b[36m${FRAMES[frame++ % FRAMES.size]}\u001b[0m $l") }
    fun finish() { if (drawn) eprint("\r\u001b[2K") }

    companion object {
        const val TICK_MS = 120
        private val FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
        internal fun shown(stderrIsTty: Boolean, env: String?) = stderrIsTty && env != "1"
        fun pick(label: String) = Spinner(label.takeIf { shown(isatty(2) == 1, getenv("RTOK_NO_PROGRESS")?.toKString()) })
        private fun eprint(s: String) { val b = s.encodeToByteArray(); b.usePinned { write(2, it.addressOf(0), b.size.convert()) } }
    }
}
