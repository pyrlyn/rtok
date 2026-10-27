// Port of src/proxy/live.rs: the in-memory live view of plain-proxy traffic (proxy or core disabled). Nothing
// is persisted; TUI/web read it via `snapshot` or `GET /live`.
package rtok.proxy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rtok.config.Config
import kotlin.concurrent.AtomicReference

/** One plain-proxy request summary for the operator surfaces; no DB row behind it. */
@Serializable data class LiveCall(
    val ts: Long,
    val method: String,
    val path: String,
    val provider: String? = null,
    val model: String? = null,
    val status: Int,
    @SerialName("request_bytes") val requestBytes: Long,
    @SerialName("response_bytes") val responseBytes: Long,
    val ms: Double,
)

/** A newest-first ring capped at [cap]; lock-free (copy on write). */
class LiveRing(
    private val cap: Int = LIVE_CAP,
) {
    private val ring = AtomicReference<List<LiveCall>>(emptyList())

    fun push(call: LiveCall) {
        while (true) {
            val cur = ring.value
            if (ring.compareAndSet(cur, (listOf(call) + cur).take(cap))) return
        }
    }

    fun snapshot(): List<LiveCall> = ring.value

    fun clear() {
        ring.value = emptyList()
    }

    companion object {
        /** Cap matches the Calls page budget. */
        const val LIVE_CAP = 120
    }
}

object Live {
    /** The process-wide ring the proxy fills. */
    val ring = LiveRing()

    /** Persistent operator warning while config keeps the proxy in plain mode. */
    fun alerts(cfg: Config): List<String> {
        val off = listOfNotNull("[proxy] enabled".takeUnless { cfg.proxy.enabled }, "[core] enabled".takeUnless { cfg.core.enabled })
        return if (off.isEmpty()) {
            emptyList()
        } else {
            listOf(
                "proxy disabled — live passthrough (not recorded). Set ${off.joinToString(" and ")} = true and restart to restore compress/bookkeeping.",
            )
        }
    }
}
