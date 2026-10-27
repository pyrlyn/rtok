// Port of the capture half of rtok-sys `proc` (`ProgressRunner::run_capped`, `exit_code`): spawn a program
// with piped stdout/stderr, keep at most `cap` bytes, and show a stderr spinner while it runs.
@file:OptIn(ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.*
import platform.posix.*
import rtok.sys.spawn.*

/** What a finished child printed and how it ended. [exitCode] is null when a signal killed it. */
class Captured(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val exitCode: Int?,
)

/** Runs [program] with [args] (no shell) and captures its output; [label] is the spinner text. */
class ProgressRunner(
    private val label: String,
    private val program: String,
    private val args: List<String>,
) {
    private var stdinPath: String? = null
    private var timeoutMs: Long? = null
    private var quiet = false
    private var dir: String? = null
    private val extraEnv = mutableMapOf<String, String>()

    /** The child's stdin is this file (Rust `.stdin(Stdio::from(file))`). */
    fun stdinFile(path: String) = apply { stdinPath = path }

    /** Kill the child and fail once it runs longer than [ms] (Rust `.timeout`). */
    fun timeout(ms: Long) = apply { timeoutMs = ms }

    /** The child starts in [path] (Rust `.cwd`). */
    fun cwd(path: String) = apply { dir = path }

    /** No spinner (Rust `ProgressRunner::quiet`). */
    fun quiet() = apply { quiet = true }

    /** Set [key] to [value] in the child's environment, on top of ours (Rust `.env`). */
    fun env(
        key: String,
        value: String,
    ) = apply { extraEnv[key] = value }

    /** Captures up to [cap] bytes over both streams; the second value counts the bytes dropped past it. */
    fun runCapped(cap: Long): Pair<Captured, Long> =
        memScoped {
            val outPipe = allocArray<IntVar>(2)
            val errPipe = allocArray<IntVar>(2)
            check(pipe(outPipe) == 0 && pipe(errPipe) == 0) { "pipe: errno $errno" }
            val actions = SpawnActions().dup2(outPipe[1], 1).dup2(errPipe[1], 2)
            dir?.let { actions.chdir(it) }
            stdinPath?.let { actions.open(0, it, O_RDONLY) }
            for (fd in listOf(outPipe[0], outPipe[1], errPipe[0], errPipe[1])) actions.close(fd)
            val spawned = runCatching { actions.spawn(program, args, if (extraEnv.isEmpty()) null else mergedEnv(extraEnv)) }
            close(outPipe[1])
            close(errPipe[1])
            spawned.exceptionOrNull()?.let {
                close(outPipe[0])
                close(errPipe[0])
                throw it
            }
            val pid = spawned.getOrThrow()
            val sinks = listOf(Sink(outPipe[0]), Sink(errPipe[0]))
            val spinner = if (quiet) Spinner.NONE else Spinner.pick(label)
            val deadline =
                timeoutMs?.let {
                    kotlin.time.TimeSource.Monotonic
                        .markNow() + with(kotlin.time.Duration) { it.milliseconds }
                }
            var budget = cap
            var dropped = 0L
            val fds = allocArray<pollfd>(2)
            val buf = ByteArray(64 * 1024)
            // Once the child exits only a finite drain follows: a descendant that inherited the pipes and outlives
            // the child must not hold the caller until it exits too (Rust ProgressRunner, T638).
            var exit: Exit? = null
            var drainEnd: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null
            while (sinks.any { it.open } && drainEnd?.hasPassedNow() != true) {
                if (exit == null) {
                    exit = reapNow(pid)
                    if (exit != null) {
                        drainEnd = kotlin.time.TimeSource.Monotonic
                            .markNow() + with(kotlin.time.Duration) { DRAIN_MS.milliseconds }
                    }
                }

                sinks.forEachIndexed { i, s ->
                    fds[i].fd = if (s.open) s.fd else -1
                    fds[i].events = POLLIN.toShort()
                    fds[i].revents = 0
                }
                if (deadline != null && deadline.hasPassedNow()) {
                    kill(pid, SIGKILL)
                    sinks.filter { it.open }.forEach { close(it.fd) }
                    waitExit(pid)
                    spinner.finish()
                    throw IoException(IoException.Kind.Other, "$program: timed out after ${timeoutMs}ms")
                }
                if (poll(fds, 2u, Spinner.TICK_MS) == 0) {
                    spinner.tick()
                    continue
                }
                sinks.forEachIndexed { i, s ->
                    if (!s.open || fds[i].revents.toInt() == 0) return@forEachIndexed
                    val n = buf.usePinned { read(s.fd, it.addressOf(0), buf.size.convert()) }.toInt()
                    if (n <= 0) {
                        if (n < 0 && errno == EINTR) return@forEachIndexed
                        s.open = false
                        close(s.fd)
                        return@forEachIndexed
                    }
                    val keep = minOf(n.toLong(), budget).toInt()
                    if (keep > 0) s.bytes.write(buf, keep)
                    budget -= keep
                    dropped += n - keep
                }
            }
            sinks.filter { it.open }.forEach {
                it.open = false
                close(it.fd)
            }
            spinner.finish()
            Captured(sinks[0].bytes.toByteArray(), sinks[1].bytes.toByteArray(), exit?.code ?: waitExit(pid)) to dropped
        }

    /** A reaped exit: the code, or null for a signal. */
    private class Exit(
        val code: Int?,
    )

    /** The child's exit when it already ended (WNOHANG), else null. */
    private fun reapNow(pid: pid_t): Exit? =
        memScoped {
            val status = alloc<IntVar>()
            if (waitpid(pid, status.ptr, WNOHANG) != pid) return null
            val s = status.value
            Exit(if (s and 0x7f == 0) (s shr 8) and 0xff else null)
        }

    private companion object {
        /** How long the pipes are drained after the child exited. */
        const val DRAIN_MS = 500L
    }

    private class Sink(
        val fd: Int,
    ) {
        var open = true
        val bytes = Bytes()
    }

    private fun waitExit(pid: pid_t): Int? =
        memScoped {
            val status = alloc<IntVar>()
            while (waitpid(pid, status.ptr, 0) < 0) if (errno != EINTR) return null
            val s = status.value
            // WIFEXITED / WEXITSTATUS, spelled out because the macros are not exported to Kotlin.
            if (s and 0x7f == 0) (s shr 8) and 0xff else null
        }
}

/** Growable byte buffer (Kotlin/Native has no `ByteArrayOutputStream`). */
internal class Bytes {
    private var a = ByteArray(4096)
    private var n = 0

    fun write(
        b: ByteArray,
        len: Int,
    ) {
        if (n + len > a.size) a = a.copyOf(maxOf(a.size * 2, n + len))
        b.copyInto(a, n, 0, len)
        n += len
    }

    fun toByteArray() = a.copyOf(n)
}

private fun environPtr() = rtok_environ()

/** Our environment as `KEY=value` entries with [extra] set on top (Rust `Command::env`). */
internal fun mergedEnv(extra: Map<String, String>): List<String> =
    buildList {
        val e = environPtr()
        var i = 0
        while (true) {
            val s = e?.get(i++)?.toKString() ?: break
            if (s.substringBefore('=') !in extra) add(s)
        }
        extra.forEach { (k, v) -> add("$k=$v") }
    }

/** Like Rust `exit_code`: the code, or 1 when a signal ended the child. */
fun exitCode(c: Captured): Int = c.exitCode ?: 1

/**
 * The stderr spinner. Shown only when stderr is a terminal and `RTOK_NO_PROGRESS` is not `1`
 * (Rust `indicator::shown`); otherwise every call is a no-op.
 */
internal class Spinner private constructor(
    private val label: String?,
) {
    private var frame = 0
    private var drawn = false

    fun tick() {
        val l = label ?: return
        drawn = true
        eprint("\r\u001b[2K\u001b[36m${FRAMES[frame++ % FRAMES.size]}\u001b[0m $l")
    }

    fun finish() {
        if (drawn) eprint("\r\u001b[2K")
    }

    companion object {
        const val TICK_MS = 120
        private val FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

        internal fun shown(
            stderrIsTty: Boolean,
            env: String?,
        ) = stderrIsTty && env != "1"

        val NONE = Spinner(null)

        fun pick(label: String) = Spinner(label.takeIf { shown(isatty(2) == 1, getenv("RTOK_NO_PROGRESS")?.toKString()) })

        private fun eprint(s: String) {
            val b = s.encodeToByteArray()
            b.usePinned { write(2, it.addressOf(0), b.size.convert()) }
        }
    }
}

/** Path of the running executable (Rust `std::env::current_exe`), or null. */
fun currentExe(): String? =
    memScoped {
        val buf = allocArray<ByteVar>(4096)
        if (rtok_current_exe(buf, 4096u) == 0) return buf.toKString()
        val n = readlink("/proc/self/exe", buf, 4095u).toInt()
        if (n > 0) {
            buf[n] = 0
            buf.toKString()
        } else {
            null
        }
    }

/**
 * Starts [program] detached (Rust `ProgressRunner::quiet(..).output(Output::Detached)`): own session,
 * stdout/stderr to /dev/null, [extraEnv] on top of ours, and [stdin] written to its stdin pipe.
 * Not waited for; false when it could not be started.
 */
fun spawnDetached(
    program: String,
    args: List<String>,
    extraEnv: Map<String, String>,
    stdin: ByteArray,
): Boolean =
    memScoped {
        val inPipe = allocArray<IntVar>(2)
        if (pipe(inPipe) != 0) return false
        val actions =
            SpawnActions()
                .dup2(inPipe[0], 0)
                .close(inPipe[0])
                .close(inPipe[1])
                .open(1, "/dev/null", O_WRONLY)
                .open(2, "/dev/null", O_WRONLY)
        val rc = if (runCatching { actions.spawn(program, args, mergedEnv(extraEnv), setsid = true, search = false) }.isSuccess) 0 else 1
        close(inPipe[0])
        if (rc == 0) stdin.usePinned { if (stdin.isNotEmpty()) write(inPipe[1], it.addressOf(0), stdin.size.convert()) }
        close(inPipe[1])
        rc == 0
    }
