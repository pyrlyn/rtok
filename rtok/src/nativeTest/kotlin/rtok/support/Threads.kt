package rtok.support

import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

/** Runs `n` copies of `body(i)` on their own threads, started together; returns each one's error message, if any. */
@OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)
fun parallel(n: Int, body: (Int) -> Unit): List<String> {
    val workers = (0 until n).map { Worker.start() }
    val futures = workers.mapIndexed { i, w ->
        w.execute(TransferMode.SAFE, { i to body }) { (k, f) -> try { f(k); null } catch (e: Throwable) { e.message ?: e.toString() } }
    }
    val errs = futures.mapNotNull { it.result }
    workers.forEach { it.requestTermination().result }
    return errs
}
