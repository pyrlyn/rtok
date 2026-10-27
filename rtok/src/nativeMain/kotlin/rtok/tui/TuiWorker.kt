// Port of src/tui/app.rs `Worker`: one background thread that turns a request into an answer off the key thread.
// Requests queued while a read runs collapse into the newest, so a slow model never builds a backlog. When the
// thread will not start the caller reads inline (fail open, D1).
package rtok.tui

import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

class TuiWorker<Q : Any, A : Any>(
    name: String,
    private val read: (Q) -> A,
) {
    private class Job<Q>(
        val seq: Long,
        val q: Q,
    )

    /** One answer: the request number it answers and the result (a failure keeps its exception). */
    class Done<A>(
        val seq: Long,
        val result: Result<A>,
    )

    private val pending = AtomicReference<Job<Q>?>(null)
    private val done = AtomicReference<Done<A>?>(null)
    private val busy = AtomicInt(0)
    private val worker: Worker? = runCatching { Worker.start(name = name) }.getOrNull()

    /** False when the thread did not start: [request] then answers inline. */
    val running: Boolean get() = worker != null

    /** Queues [q] as request [seq], replacing any request not yet started. */
    fun request(
        seq: Long,
        q: Q,
    ) {
        pending.value = Job(seq, q)
        val w = worker
        if (w == null) {
            drain()
            return
        }
        if (busy.compareAndSet(0, 1)) {
            val started = runCatching { w.execute(TransferMode.SAFE, { this }) { it.loop() } }
            if (started.isFailure) {
                busy.value = 0
                drain()
            }
        }
    }

    /** The newest answer since the last poll, or null. */
    fun poll(): Done<A>? = done.getAndSet(null)

    fun close() {
        worker?.requestTermination(processScheduledJobs = false)
    }

    private fun loop() {
        while (true) {
            drain()
            busy.value = 0
            // A request that landed between the drain and the flag drop would otherwise wait for the next one.
            if (pending.value == null || !busy.compareAndSet(0, 1)) return
        }
    }

    private fun drain() {
        while (true) {
            val job = pending.getAndSet(null) ?: return
            done.value = Done(job.seq, runCatching { read(job.q) })
        }
    }
}
