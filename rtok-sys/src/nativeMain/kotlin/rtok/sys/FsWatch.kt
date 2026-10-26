// The recursive file watcher `graph::watch` needs (the part of the `notify` crate rtok uses): FSEvents on macOS,
// inotify on Linux, both behind [FsWatch] so the debounce loop and its tests never see the OS API.
package rtok.sys

/** One delivery: the paths it names, and whether the OS lost events so the whole tree must be rescanned. */
class FsEvent(
    val paths: List<String>,
    val rescan: Boolean = false,
)

/** A live recursive watch. */
interface FsWatch : AutoCloseable {
    /** The next event, waiting at most [timeoutMs]; null when none came. Throws [FsWatchClosed] once the watch died. */
    fun next(timeoutMs: Long): FsEvent?
}

class FsWatchClosed : Exception("watch closed")

/** Starts watching [root] and everything below it; throws [IoException] when the OS refuses. */
expect fun watchRecursive(root: String): FsWatch

/** A thread-safe hand-off for watchers whose OS callback runs on another thread. */
class FsEventQueue : FsWatch {
    private val lock = Mutex()
    private val queue = ArrayDeque<FsEvent>()
    private var closed = false

    fun push(e: FsEvent) = lock.hold { if (!closed) queue.addLast(e) }

    /** No more events will come; [next] throws after draining. */
    fun end() = lock.hold { closed = true }

    override fun next(timeoutMs: Long): FsEvent? {
        val deadline =
            kotlin.time.TimeSource.Monotonic
                .markNow() + with(kotlin.time.Duration) { timeoutMs.milliseconds }
        while (true) {
            lock.hold {
                queue.removeFirstOrNull()?.let { return it }
                if (closed) throw FsWatchClosed()
            }
            if (deadline.hasPassedNow()) return null
            platform.posix.usleep(STEP_US)
        }
    }

    override fun close() = end()

    private companion object {
        const val STEP_US = 5_000u
    }
}
