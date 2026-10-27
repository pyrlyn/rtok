// Port of fs-kit `tree::usage_until` (rtok's `disk_usage_until`): allocated bytes under a path, hard links once.
package rtok.sys

import kotlin.time.TimeSource

/** Bytes on disk and files under a path; [denied] when a read was refused, [timedOut] when the deadline cut the walk. */
data class DiskUsage(
    val bytes: Long = 0,
    val files: Long = 0,
    val denied: Boolean = false,
    val timedOut: Boolean = false,
)

/** `usage_until`: symlinks are not followed and cost their own blocks; a walk past [deadline] is a lower bound. */
fun diskUsageUntil(
    path: String,
    deadline: TimeSource.Monotonic.ValueTimeMark?,
): DiskUsage = DiskWalk(deadline).also { it.walk(path) }.usage

/** `st_blocks` counts 512-byte units on every Unix rtok targets. */
private const val BLOCK = 512L

private class DiskWalk(
    private val deadline: TimeSource.Monotonic.ValueTimeMark?,
) {
    var usage = DiskUsage()
    private val seen = HashSet<Pair<Long, Long>>()

    fun walk(path: String) {
        if (usage.timedOut || deadline?.hasPassedNow() == true) {
            usage = usage.copy(timedOut = true)
        } else {
            val meta = attempt { symlinkMeta(path) }
            if (meta != null) visit(path, meta)
        }
    }

    private fun visit(
        path: String,
        meta: Meta,
    ) {
        val bytes = if (meta.nlink < 2 || seen.add(meta.dev to meta.ino)) meta.blocks * BLOCK else 0
        val dir = meta.kind == EntryKind.Dir
        usage = usage.copy(bytes = usage.bytes + bytes, files = usage.files + if (dir) 0 else 1)
        if (dir) attempt { readDir(path) }?.forEach { walk("$path/$it") }
    }

    /** [read], or null with a refused read noted as [DiskUsage.denied]. */
    private fun <T> attempt(read: () -> T): T? =
        try {
            read()
        } catch (e: IoException) {
            deny(e)
            null
        }

    private fun deny(e: IoException) {
        if (e.kind == IoException.Kind.PermissionDenied) usage = usage.copy(denied = true)
    }
}
