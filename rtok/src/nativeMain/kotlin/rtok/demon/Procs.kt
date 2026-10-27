@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.demon

import platform.posix.SIGKILL
import platform.posix.SIGTERM
import platform.posix.kill
import platform.posix.usleep
import rtok.sys.ProgressRunner
import rtok.sys.exists
import rtok.sys.processAlive
import rtok.sys.readToString

/** The process table as the verbs see it; an interface so tests record signals instead of sending them. */
interface Procs {
    /** True while [pid] is a live process; never true for 0 or a negative pid (a process group). */
    fun alive(pid: Int): Boolean

    fun term(pid: Int)

    fun kill(pid: Int)

    /** Unix seconds the machine booted, or null when nothing tells. */
    fun bootTime(): Long?

    fun pause(ms: Long)
}

/** The real process table. */
object OsProcs : Procs {
    override fun alive(pid: Int) = processAlive(pid)

    override fun term(pid: Int) {
        if (pid > 0) kill(pid, SIGTERM)
    }

    override fun kill(pid: Int) {
        if (pid > 0) kill(pid, SIGKILL)
    }

    override fun bootTime(): Long? = bootSeconds()

    override fun pause(ms: Long) {
        usleep((ms * MICROS).toUInt())
    }

    private const val MICROS = 1000L
}

/** `/proc/stat` `btime` on Linux, `sysctl -n kern.boottime` (`{ sec = N, usec = M } ...`) on macOS. */
internal fun bootSeconds(): Long? {
    if (exists("/proc/stat")) {
        return runCatching { readToString("/proc/stat") }.getOrNull()?.lines()?.firstNotNullOfOrNull {
            it
                .removePrefix("btime ")
                .takeIf { rest -> rest != it }
                ?.trim()
                ?.toLongOrNull()
        }
    }
    val out = runCatching { ProgressRunner("", "sysctl", listOf("-n", "kern.boottime")).quiet().runCapped(CAP).first }.getOrNull()
    return out?.let { parseBootTime(it.stdout.decodeToString()) }
}

/** The seconds of a `kern.boottime` line. */
private const val CAP = 4096L

internal fun parseBootTime(s: String): Long? = s.substringAfter("sec = ", "").takeWhile { it.isDigit() }.toLongOrNull()
