// Port of the store test helper `hold_write_lock` (HeldWrite::Immediate): another connection holds the write lock.
package rtok.support

import rtok.db.Db
import kotlin.native.concurrent.Future
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

private const val MICROS = 1000u

/** A held `BEGIN IMMEDIATE` on [path] that commits after [ms]; `join` waits for it. */
class HeldWriteLock(
    private val worker: Worker,
    private val done: Future<Unit>,
) {
    fun join() {
        done.result
        worker.requestTermination().result
    }
}

fun holdWriteLock(
    path: String,
    ms: Long,
): HeldWriteLock {
    val holder = Db.open(path)
    holder.exec("PRAGMA journal_mode = WAL")
    holder.exec("BEGIN IMMEDIATE")
    val w = Worker.start()
    val f =
        w.execute(TransferMode.SAFE, { holder to ms }) { (h, wait) ->
            platform.posix.usleep(wait.toUInt() * MICROS)
            h.exec("COMMIT")
            h.close()
        }
    return HeldWriteLock(w, f)
}
