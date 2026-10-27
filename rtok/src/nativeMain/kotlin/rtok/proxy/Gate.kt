// Port of src/proxy/gate.rs: per-lane in-flight cap (T385.7). Each capped lane has its own slots; past the
// slots a few requests wait in line; past the line the caller gets `429` + `Retry-After`.
package rtok.proxy

import kotlinx.coroutines.sync.Semaphore
import kotlin.concurrent.AtomicInt

class Gate private constructor(
    maxInFlight: Int,
    private val maxQueued: Int,
) {
    private val slots = Semaphore(maxInFlight)
    private val waiting = AtomicInt(0)

    /** Holding one is holding an upstream slot on its lane; [release] frees it (once). */
    inner class Slot {
        private val held = AtomicInt(1)

        fun release() {
            if (held.compareAndSet(1, 0)) slots.release()
        }
    }

    /** A slot now, or after waiting in line; null when the line is full. */
    suspend fun enter(): Slot? =
        when {
            slots.tryAcquire() -> {
                Slot()
            }

            waiting.getAndAdd(1) >= maxQueued -> {
                waiting.getAndAdd(-1)
                null
            }

            else -> {
                // Released however the wait ends, a cancelled (hung-up) caller included.
                try {
                    slots.acquire()
                } finally {
                    waiting.getAndAdd(-1)
                }
                Slot()
            }
        }

    /** Requests waiting for a slot right now. */
    fun waiting(): Int = waiting.value

    companion object {
        /** What a caller turned away by a full queue is told to wait before retrying. */
        const val RETRY_AFTER_S: Long = 1

        /** null for `maxInFlight = 0`: that lane is not capped. */
        fun of(
            maxInFlight: UInt,
            maxQueued: UInt,
        ): Gate? = if (maxInFlight > 0u) Gate(maxInFlight.toInt(), maxQueued.toInt()) else null
    }
}
