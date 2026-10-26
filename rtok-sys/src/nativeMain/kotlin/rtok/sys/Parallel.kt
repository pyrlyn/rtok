@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.native.concurrent.ObsoleteWorkersApi::class)

package rtok.sys

import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.sysconf
import kotlin.native.concurrent.Future
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

/** Online CPU count, at least 1 (Rust `available_parallelism`). */
fun availableParallelism(): Int = sysconf(_SC_NPROCESSORS_ONLN).toInt().coerceAtLeast(1)

/**
 * Runs [work] over [items] on up to one worker per core and hands each result to [sink] on the calling
 * thread in [items] order, so the caller sees exactly the calls of a sequential run. At most two results
 * per worker are in flight, so a slow sink stalls the workers instead of piling results up in memory.
 * An exception from [sink] stops submitting; in-flight work finishes and is dropped.
 * [work] must not touch state shared with the caller beyond read-only data.
 */
fun <T, R> eachOrdered(
    items: List<T>,
    work: (T) -> R,
    sink: (T, R) -> Unit,
) {
    val n = minOf(availableParallelism(), items.size)
    if (n <= 1) {
        for (it in items) sink(it, work(it))
        return
    }
    val workers = List(n) { Worker.start(name = "rtok-pool-$it") }
    try {
        val inFlight = ArrayDeque<Future<R>>()
        var next = 0

        fun submit() {
            val item = items[next]
            val w = workers[next % n]
            next++
            inFlight.addLast(w.execute(TransferMode.SAFE, { work to item }) { (f, x) -> f(x) })
        }
        while (next < items.size && inFlight.size < n * 2) submit()
        var due = 0
        while (inFlight.isNotEmpty()) {
            val r = inFlight.removeFirst().result
            sink(items[due], r)
            due++
            if (next < items.size) submit()
        }
    } finally {
        for (w in workers) w.requestTermination().result
    }
}
