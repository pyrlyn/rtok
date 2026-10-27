// Port of src/web/mod.rs `snapshot_shared` (T206): at most one snapshot build at a time; ticks that land
// while one runs share its result instead of starting another.
package rtok.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Builds one snapshot frame (JSON). Blocking: it reads the store. */
fun interface BuildFn {
    fun build(): String
}

class Snapshots(
    private val buildFn: BuildFn,
) {
    private val slot = Mutex()
    private var inFlight: CompletableDeferred<String>? = null

    /** The frame for one tick; the build runs off the caller's thread so `/health` never waits on it. */
    suspend fun shared(): String {
        val mine = CompletableDeferred<String>()
        val running = slot.withLock { inFlight ?: mine.also { inFlight = it } }
        if (running !== mine) return running.await()
        val snap =
            runCatching { withContext(Dispatchers.Default) { buildFn.build() } }
                .getOrElse { """{"error":"snapshot build failed","type":"snapshot"}""" }
        slot.withLock { inFlight = null }
        mine.complete(snap)
        return snap
    }
}

/**
 * protocol.rs `tag_reply`: a reply to a request that carried `req` gets it back first in the object, so a page
 * with several requests in flight matches each answer to its question (T623).
 */
fun tagReply(
    frame: String,
    req: Long?,
): String = if (req != null && frame.startsWith("{\"")) "{\"req\":$req,${frame.substring(1)}" else frame
