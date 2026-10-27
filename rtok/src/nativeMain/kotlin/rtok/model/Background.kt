// Port of src/model/mod.rs `Background` (T231, T232): a page read too slow for a 2 s tick. The tick renders the
// last known value while at most one worker refreshes it once `ttl` has passed; a cold or stale entry never blocks.
package rtok.model

import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class Background<T : Any>(
    private val name: String,
) {
    private class Entry<T>(
        val at: TimeMark,
        val value: T,
    )

    private val cache = AtomicReference<Entry<T>?>(null)
    private val refreshing = AtomicInt(0)
    private val worker by lazy { Worker.start(name = name) }

    fun forget() = cache.value.let { cache.value = null }

    /**
     * The last known value if younger than [ttl]; otherwise starts at most one background [read] (skipped while one
     * is in flight) and returns at once with the last known value, or null before any read has landed.
     */
    fun get(
        ttl: Duration,
        read: () -> T,
    ): T? {
        val last = cache.value
        val fresh = last != null && last.at.elapsedNow() < ttl
        if (!fresh && refreshing.compareAndSet(0, 1)) {
            val started =
                runCatching {
                    worker.execute(TransferMode.SAFE, { this to read }) { (bg, job) -> bg.land(job) }
                }
            if (started.isFailure) refreshing.value = 0
        }
        return last?.value
    }

    private fun land(read: () -> T) {
        try {
            runCatching(read).getOrNull()?.let { cache.value = Entry(TimeSource.Monotonic.markNow(), it) }
        } finally {
            refreshing.value = 0
        }
    }
}
