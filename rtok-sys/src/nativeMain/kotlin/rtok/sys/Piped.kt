// A child process with piped stdin and stdout for a request/response peer such as a language server
// (Rust `Command::spawn` with `Stdio::piped()` plus the reader/writer threads of graph/lsp.rs). Single-threaded:
// stdin is written without blocking from a queue, and [PipedChild.read] polls stdout with a deadline, so a peer
// that stops answering or stops reading can never block the caller.
@file:OptIn(ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.*
import platform.posix.*
import rtok.sys.spawn.*
import kotlin.time.TimeSource

class PipedChild private constructor(
    val pid: Int,
    private var inFd: Int,
    private var outFd: Int,
) {
    private val queue = ArrayDeque<ByteArray>()
    private var head = 0
    private var status: Int? = null
    private var reaped = false
    private var closeWhenDrained = false

    /** Bytes still queued for the child's stdin. */
    val pending: Int get() = queue.sumOf { it.size } - head

    /** Queues [bytes] for stdin and writes what the pipe takes now; false once stdin is closed. */
    fun send(bytes: ByteArray): Boolean {
        if (inFd < 0) return false
        if (bytes.isNotEmpty()) queue.addLast(bytes)
        flush()
        return inFd >= 0
    }

    /** Closes stdin once every queued byte is written (Rust dropping the child's stdin handle). */
    fun finishInput() {
        closeWhenDrained = true
        flush()
    }

    /**
     * Blocks until stdout or [fd] (ignored when negative) is readable or hung up, writing queued stdin meanwhile.
     * The first flag is stdout, the second [fd]; read stdout with [read] afterwards.
     */
    fun awaitReadable(fd: Int): Pair<Boolean, Boolean> =
        memScoped {
            if (outFd < 0) return true to false
            val fds = allocArray<pollfd>(3)
            while (true) {
                val writing = inFd >= 0 && queue.isNotEmpty()
                fds[0].fd = outFd
                fds[0].events = POLLIN.toShort()
                fds[0].revents = 0
                fds[1].fd = fd
                fds[1].events = POLLIN.toShort()
                fds[1].revents = 0
                fds[2].fd = if (writing) inFd else -1
                fds[2].events = POLLOUT.toShort()
                fds[2].revents = 0
                val r = poll(fds, 3u, -1)
                if (r < 0 && errno != EINTR) return true to false
                if (r <= 0) continue
                if (fds[2].revents.toInt() != 0) {
                    if (fds[2].revents.toInt() and (POLLERR or POLLHUP) != 0) closeInput() else flush()
                }
                val child = fds[0].revents.toInt() != 0
                val other = fd >= 0 && fds[1].revents.toInt() != 0
                if (child || other) return child to other
            }
            @Suppress("UNREACHABLE_CODE")
            true to false
        }

    /** Writes queued bytes until the pipe would block; a broken pipe closes stdin. */
    private fun flush() {
        while (inFd >= 0 && queue.isNotEmpty()) {
            val b = queue.first()
            val n = b.usePinned { write(inFd, it.addressOf(head), (b.size - head).convert()) }.toInt()
            if (n < 0) {
                if (errno == EINTR) continue
                if (errno != EAGAIN) closeInput()
                return
            }
            head += n
            if (head == b.size) {
                queue.removeFirst()
                head = 0
            }
        }
        if (closeWhenDrained && queue.isEmpty()) closeInput()
    }

    private fun closeInput() {
        if (inFd >= 0) close(inFd)
        inFd = -1
        queue.clear()
        head = 0
    }

    /**
     * Waits until stdout has bytes or [deadline] passes, writing queued stdin meanwhile. The bytes read, an
     * empty array at end of file, or null on timeout.
     */
    fun read(deadline: TimeSource.Monotonic.ValueTimeMark): ByteArray? =
        memScoped {
            if (outFd < 0) return ByteArray(0)
            val fds = allocArray<pollfd>(2)
            val buf = ByteArray(READ_CHUNK)
            while (true) {
                val left = -deadline.elapsedNow().inWholeMilliseconds
                if (left <= 0) return null
                val writing = inFd >= 0 && queue.isNotEmpty()
                fds[0].fd = outFd
                fds[0].events = POLLIN.toShort()
                fds[0].revents = 0
                fds[1].fd = if (writing) inFd else -1
                fds[1].events = POLLOUT.toShort()
                fds[1].revents = 0
                val r = poll(fds, 2u, minOf(left, Int.MAX_VALUE.toLong()).toInt())
                if (r < 0 && errno != EINTR) return ByteArray(0)
                if (r <= 0) continue
                if (fds[1].revents.toInt() != 0) {
                    if (fds[1].revents.toInt() and (POLLERR or POLLHUP) != 0) closeInput() else flush()
                }
                if (fds[0].revents.toInt() == 0) continue
                val n = buf.usePinned { read(outFd, it.addressOf(0), buf.size.convert()) }.toInt()
                if (n < 0 && errno == EINTR) continue
                if (n <= 0) {
                    close(outFd)
                    outFd = -1
                    return ByteArray(0)
                }
                return buf.copyOf(n)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    /** The exit status once the child ended (Rust `try_wait`): the code, or -signal. Never blocks. */
    fun tryWait(): Int? {
        if (reaped) return status
        memScoped {
            val st = alloc<IntVar>()
            val r = waitpid(pid, st.ptr, WNOHANG)
            if (r == pid) {
                reaped = true
                status = decodeStatus(st.value)
            }
        }
        return status
    }

    /** Blocks until the child exits and reaps it (Rust `wait`): the code, or -signal. */
    fun waitExit(): Int {
        if (!reaped) {
            memScoped {
                val st = alloc<IntVar>()
                while (waitpid(pid, st.ptr, 0) < 0) if (errno != EINTR) return -1
                reaped = true
                status = decodeStatus(st.value)
            }
        }
        return status ?: -1
    }

    /** Kills and reaps the child and closes both pipes (Rust `kill` + `wait`). */
    fun kill() {
        closeInput()
        if (outFd >= 0) close(outFd)
        outFd = -1
        if (reaped) return
        kill(pid, SIGKILL)
        memScoped {
            val st = alloc<IntVar>()
            while (waitpid(pid, st.ptr, 0) < 0) if (errno != EINTR) break
            reaped = true
            status = decodeStatus(st.value)
        }
    }

    companion object {
        private const val SIGNAL_MASK = 0x7f
        private const val CODE_SHIFT = 8
        private const val BYTE = 0xff
        private const val READ_CHUNK = 64 * 1024
        private const val MODE_644 = 0x1a4

        /** WIFEXITED/WEXITSTATUS spelled out (the macros are not exported): the code, or -signal. */
        private fun decodeStatus(s: Int): Int = if (s and SIGNAL_MASK == 0) (s shr CODE_SHIFT) and BYTE else -(s and SIGNAL_MASK)

        /**
         * Starts [program] (looked up on PATH when it has no slash) in [cwd] with [extraEnv] on top of ours and
         * stderr to [stderrPath] (or /dev/null). Throws [IoException] when it cannot be started.
         */
        fun spawn(
            program: String,
            args: List<String>,
            cwd: String? = null,
            extraEnv: Map<String, String> = emptyMap(),
            stderrPath: String? = null,
            inheritStderr: Boolean = false,
        ): PipedChild =
            memScoped {
                // A peer that exits must fail the write, not kill rtok (Rust ignores SIGPIPE at startup).
                signal(SIGPIPE, SIG_IGN)
                val inPipe = allocArray<IntVar>(2)
                val outPipe = allocArray<IntVar>(2)
                check(pipe(inPipe) == 0 && pipe(outPipe) == 0) { "pipe: errno $errno" }
                val actions = alloc<posix_spawn_file_actions_tVar>()
                posix_spawn_file_actions_init(actions.ptr)
                posix_spawn_file_actions_adddup2(actions.ptr, inPipe[0], 0)
                posix_spawn_file_actions_adddup2(actions.ptr, outPipe[1], 1)
                if (!inheritStderr) {
                    posix_spawn_file_actions_addopen(
                        actions.ptr,
                        2,
                        stderrPath ?: "/dev/null",
                        O_WRONLY or O_CREAT or O_TRUNC,
                        MODE_644.convert(),
                    )
                }
                for (fd in listOf(inPipe[0], inPipe[1], outPipe[0], outPipe[1])) posix_spawn_file_actions_addclose(actions.ptr, fd)
                cwd?.let { rtok_spawn_addchdir(actions.ptr, it) }
                val argv = allocArrayOf((listOf(program) + args).map { it.cstr.ptr } + listOf(null))
                val envp = allocArrayOf(mergedEnv(extraEnv).map { it.cstr.ptr } + listOf(null))
                val pid = alloc<pid_tVar>()
                val rc =
                    if ('/' in program) {
                        posix_spawn(pid.ptr, program, actions.ptr, null, argv, envp)
                    } else {
                        posix_spawnp(pid.ptr, program, actions.ptr, null, argv, envp)
                    }
                posix_spawn_file_actions_destroy(actions.ptr)
                close(inPipe[0])
                close(outPipe[1])
                if (rc != 0) {
                    close(inPipe[1])
                    close(outPipe[0])
                    throw IoException(if (rc == ENOENT) IoException.Kind.NotFound else IoException.Kind.Other, "$program: spawn errno $rc")
                }
                fcntl(inPipe[1], F_SETFL, fcntl(inPipe[1], F_GETFL) or O_NONBLOCK)
                fcntl(inPipe[1], F_SETFD, FD_CLOEXEC)
                fcntl(outPipe[0], F_SETFD, FD_CLOEXEC)
                PipedChild(pid.value, inPipe[1], outPipe[0])
            }
    }
}
