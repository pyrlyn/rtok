// Port of src/hooks/deadline.rs — a hook call that outlives its deadline prints `{}` and exits 0.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class, kotlin.native.concurrent.ObsoleteWorkersApi::class)
package rtok.hooks

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import platform.posix.*

/** Who writes the answer: the first to [claim] prints; the other stays silent. */
class Gate {
    private val taken = AtomicBoolean(false)
    fun claim(): Boolean = !taken.exchange(true)
}

/** Stops the watchdog armed by [arm] when closed. */
class Armed internal constructor(private val stop: AtomicBoolean, private val worker: Worker) : AutoCloseable {
    override fun close() { stop.store(true); worker.requestTermination(processScheduledJobs = false) }
}

/** Run `expired` on a watchdog worker once `limitMs` passes without the returned [Armed] being closed. */
fun arm(limitMs: Long, expired: () -> Unit): Armed {
    val stop = AtomicBoolean(false); val w = Worker.start(name = "rtok-deadline")
    w.execute(TransferMode.SAFE, { Triple(stop, limitMs, expired) }) { (stop, limit, fire) ->
        val end = now() + limit
        while (!stop.load()) {
            val left = end - now(); if (left <= 0) { if (!stop.load()) fire(); break }
            usleep((minOf(left, 10L) * 1000).toUInt())
        }
    }
    return Armed(stop, w)
}

private fun now(): Long = MONO.elapsedNow().inWholeMilliseconds
private val MONO = kotlin.time.TimeSource.Monotonic.markNow()

/** [arm] with the real expiry: claim `gate`, write `{}` to stdout, note it on stderr, exit 0. */
fun armStdout(event: String, limitMs: Long, gate: Gate): Armed = arm(limitMs) {
    if (gate.claim()) {
        fputs("rtok: hook $event passed its $limitMs ms deadline; failing open\n", stderr)
        fputs("{}", stdout); fflush(stdout); exit(0)
    }
}
