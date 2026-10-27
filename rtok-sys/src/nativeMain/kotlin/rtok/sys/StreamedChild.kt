// A child with its stdin on /dev/null and both stdout and stderr piped back, read line by line with a poll timeout
// (Rust `Command` with `Stdio::piped()` for both streams plus two reader threads, as `rtok demon supervise` uses).
// Single-threaded: [StreamedChild.pump] polls both pipes, so a quiet child never blocks the caller.
@file:OptIn(ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.*
import platform.posix.*
import rtok.sys.spawn.*

class StreamedChild private constructor(
    val pid: Int,
    outFd: Int,
    errFd: Int,
) {
    private val fds = intArrayOf(outFd, errFd)
    private val partial = arrayOf(Bytes(), Bytes())
    private var status: Int? = null

    /** True once both pipes reached end of file. */
    val drained: Boolean get() = fds.all { it < 0 }

    /**
     * Waits up to [timeoutMs] for output and hands every complete line to [line] (`false` for stdout, `true` for
     * stderr). At end of file a trailing unterminated line is handed over too.
     */
    fun pump(
        timeoutMs: Int,
        line: (Boolean, String) -> Unit,
    ): Unit =
        memScoped {
            if (drained) return
            val p = allocArray<pollfd>(2)
            for (i in 0..1) {
                p[i].fd = fds[i]
                p[i].events = POLLIN.toShort()
                p[i].revents = 0
            }
            if (poll(p, 2u, timeoutMs) <= 0) return
            for (i in 0..1) if (p[i].revents.toInt() != 0) readOne(i, line)
        }

    private fun readOne(
        i: Int,
        line: (Boolean, String) -> Unit,
    ) {
        val buf = ByteArray(CHUNK)
        var n: Int
        do {
            n = buf.usePinned { read(fds[i], it.addressOf(0), buf.size.convert()) }.toInt()
        } while (n < 0 && errno == EINTR)
        if (n > 0) return split(i, buf.copyOf(n), line)
        close(fds[i])
        fds[i] = -1
        partial[i].toByteArray().takeIf { it.isNotEmpty() }?.let { line(i == 1, it.decodeToString()) }
        partial[i] = Bytes()
    }

    private fun split(
        i: Int,
        chunk: ByteArray,
        line: (Boolean, String) -> Unit,
    ) {
        var start = 0
        for (j in chunk.indices) {
            if (chunk[j] != '\n'.code.toByte()) continue
            chunk.copyOfRange(start, j).let { partial[i].write(it, it.size) }
            line(i == 1, partial[i].toByteArray().decodeToString())
            partial[i] = Bytes()
            start = j + 1
        }
        if (start < chunk.size) chunk.copyOfRange(start, chunk.size).let { partial[i].write(it, it.size) }
    }

    /** The exit status once the child ended (Rust `try_wait`): the code, or -signal. Never blocks. */
    fun tryWait(): Int? {
        if (status != null) return status
        memScoped {
            val st = alloc<IntVar>()
            if (waitpid(pid, st.ptr, WNOHANG) == pid) status = decode(st.value)
        }
        return status
    }

    /** Kills and reaps the child, then reads what is left in both pipes (Rust `kill` + `wait`). */
    fun kill(line: (Boolean, String) -> Unit) {
        if (status == null) {
            kill(pid, SIGKILL)
            memScoped {
                val st = alloc<IntVar>()
                while (waitpid(pid, st.ptr, 0) < 0) if (errno != EINTR) break
                status = decode(st.value)
            }
        }
        while (!drained) pump(DRAIN_MS, line)
    }

    companion object {
        private const val CHUNK = 64 * 1024
        private const val DRAIN_MS = 100
        private const val SIGNAL_MASK = 0x7f
        private const val CODE_SHIFT = 8
        private const val BYTE = 0xff

        private fun decode(s: Int): Int = if (s and SIGNAL_MASK == 0) (s shr CODE_SHIFT) and BYTE else -(s and SIGNAL_MASK)

        /** Starts [program] (an absolute path) with [args]; throws [IoException] when it cannot be started. */
        fun spawn(
            program: String,
            args: List<String>,
        ): StreamedChild =
            memScoped {
                val out = allocArray<IntVar>(2)
                val err = allocArray<IntVar>(2)
                check(pipe(out) == 0 && pipe(err) == 0) { "pipe: errno $errno" }
                // The parent's read ends must not leak into a later child of this process.
                fcntl(out[0], F_SETFD, FD_CLOEXEC)
                fcntl(err[0], F_SETFD, FD_CLOEXEC)
                val actions = alloc<posix_spawn_file_actions_tVar>()
                posix_spawn_file_actions_init(actions.ptr)
                posix_spawn_file_actions_addopen(actions.ptr, 0, "/dev/null", O_RDONLY, 0u)
                posix_spawn_file_actions_adddup2(actions.ptr, out[1], 1)
                posix_spawn_file_actions_adddup2(actions.ptr, err[1], 2)
                for (fd in listOf(out[0], out[1], err[0], err[1])) posix_spawn_file_actions_addclose(actions.ptr, fd)
                val argv = allocArrayOf((listOf(program) + args).map { it.cstr.ptr } + listOf(null))
                val envp = allocArrayOf(mergedEnv(emptyMap()).map { it.cstr.ptr } + listOf(null))
                val pid = alloc<pid_tVar>()
                val rc = posix_spawn(pid.ptr, program, actions.ptr, null, argv, envp)
                posix_spawn_file_actions_destroy(actions.ptr)
                close(out[1])
                close(err[1])
                if (rc != 0) {
                    close(out[0])
                    close(err[0])
                    throw IoException(if (rc == ENOENT) IoException.Kind.NotFound else IoException.Kind.Other, "$program: spawn errno $rc")
                }
                StreamedChild(pid.value, out[0], err[0])
            }

        /** Starts [program] in its own session with all three streams on /dev/null; its pid, or null. */
        fun spawnDaemon(
            program: String,
            args: List<String>,
        ): Int? =
            memScoped {
                val actions = alloc<posix_spawn_file_actions_tVar>()
                posix_spawn_file_actions_init(actions.ptr)
                posix_spawn_file_actions_addopen(actions.ptr, 0, "/dev/null", O_RDONLY, 0u)
                posix_spawn_file_actions_addopen(actions.ptr, 1, "/dev/null", O_WRONLY, 0u)
                posix_spawn_file_actions_addopen(actions.ptr, 2, "/dev/null", O_WRONLY, 0u)
                val attr = alloc<posix_spawnattr_tVar>()
                posix_spawnattr_init(attr.ptr)
                posix_spawnattr_setflags(attr.ptr, POSIX_SPAWN_SETSID.toShort())
                val argv = allocArrayOf((listOf(program) + args).map { it.cstr.ptr } + listOf(null))
                val envp = allocArrayOf(mergedEnv(emptyMap()).map { it.cstr.ptr } + listOf(null))
                val pid = alloc<pid_tVar>()
                val rc = posix_spawn(pid.ptr, program, actions.ptr, attr.ptr, argv, envp)
                posix_spawn_file_actions_destroy(actions.ptr)
                posix_spawnattr_destroy(attr.ptr)
                if (rc == 0) pid.value else null
            }
    }
}
