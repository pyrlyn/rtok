// Port of src/plugins/compress/queue.rs — T385.9.2: the background queue of the P28 compressor. The proxy
// hands over the archive ids a request just rewrote and returns at once; each job writes its summary beside
// the archive row, so a request only ever reads what an earlier job left.
package rtok.plugins.compress

import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rtok.config.Config
import rtok.store.Store
import rtok.store.setStoreRaw

/** Ids remembered as already tried; cleared when full so a long-lived proxy stays bounded. */
private const val SEEN_CAP = 10_000

class Queue internal constructor(private val compressor: Compressor, private val store: Store, maxInFlight: UInt,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
    private val maxInFlight = maxInFlight.coerceAtLeast(1u).toInt()
    private val seen = AtomicReference<Set<String>>(emptySet())
    private val inFlight = AtomicInt(0)
    internal val running get() = inFlight.value

    companion object {
        /** Null while the flag is off. Its own store handle: a job outlives the request that offered it. */
        fun create(cfg: Config): Queue? {
            val c = Compressor.create(cfg) ?: return null
            val store = Store.open(cfg.core.dbPath).also { it.setStoreRaw(cfg.core.storeRaw) }
            return Queue(c, store, cfg.plugins.compress.llm.maxInFlight)
        }
    }

    /** Queue [ids] and return without waiting; no credential leaves the extractive view. An id is tried once
     *  per process; ids past the in-flight cap are not taken, so the next offer starts them. */
    fun submit(auth: Auth?, ids: List<String>) {
        if (auth == null) return
        for (id in ids) when (val a = admit(id)) {
            Admit.Full -> return
            Admit.Seen -> continue
            is Admit.Run -> scope.launch { a.slot.use { compressor.run(store, auth, id) } }
        }
    }

    /** T655: the slot is reserved before the id is marked seen, in one CAS, so concurrent submits cannot both
     *  pass the cap and an id refused at the cap stays retryable; a refused id hands its slot back. */
    internal fun admit(id: String): Admit {
        while (true) {
            val n = inFlight.value
            if (n >= maxInFlight) return Admit.Full
            if (inFlight.compareAndSet(n, n + 1)) break
        }
        val slot = Slot()
        while (true) {
            val cur = seen.value
            if (id in cur) { slot.close(); return Admit.Seen }
            val next = (if (cur.size >= SEEN_CAP) emptySet() else cur) + id
            if (seen.compareAndSet(cur, next)) return Admit.Run(slot)
        }
    }

    /** Frees the reservation exactly once, even when a job throws. */
    internal inner class Slot : AutoCloseable {
        private val held = AtomicInt(1)
        override fun close() { if (held.compareAndSet(1, 0)) inFlight.decrementAndGet() }
    }

    internal sealed class Admit { data object Full : Admit(); data object Seen : Admit(); class Run(val slot: Slot) : Admit() }
}
