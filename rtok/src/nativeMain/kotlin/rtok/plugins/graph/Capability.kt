// Port of src/plugins/graph/capability.rs (T329.11): which graph mode works for a project, checked once. The record
// is held in memory for the hot path and mirrored into the store (`plugin_state`), so another process sees what this
// one decided. Only a `backend` change and [Capability.reprobe] (the health check) replace a record.
package rtok.plugins.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rtok.plugin.sdk.Ctx
import rtok.sys.Mutex
import kotlin.concurrent.AtomicReference

/** The mode that answers a project's requests. */
@Serializable
enum class Chosen {
    @SerialName("lsp")
    Lsp,

    @SerialName("tags")
    Tags,
}

/** One project's record, as stored and as `rtok graph projects --json` prints it (serde field names kept). */
@Serializable
data class CapabilityRecord(
    val backend: Chosen,
    val language: String? = null,
    val server: Boolean = false,
    val reason: String? = null,
    val config: String = "",
    @SerialName("checked_at") val checkedAt: Long = 0,
    @SerialName("next_probe_at") val nextProbeAt: Long? = null,
    /** The server was installed and working, then broke: what the health check raises "backend down" for. */
    val down: Boolean = false,
)

/** Why the language server cannot be used. */
class Absent(
    val server: Boolean,
    val reason: String,
)

/** The cheap half of the check (marker file and server binary, no spawn): null when the server can be used. */
typealias Probe = (root: String) -> Absent?

/** A language server's refusal of one request; unlike any other failure it does not mean the server broke. */
class Refused(
    message: String,
) : Exception(message)

/** What the door gets back for one request. */
sealed interface Asked {
    /** The server is not used for this project. */
    data class Tags(
        val rec: CapabilityRecord,
    ) : Asked

    /** The server answered or failed this request. */
    data class Lsp(
        val answer: Result<String>,
    ) : Asked
}

object Capability {
    /** Seconds until a failed record may be checked again, and how long a new process trusts a peer's record. */
    const val RECHECK_S = 60L

    private class State {
        val lock = Mutex()
        var rec: CapabilityRecord? = null

        /** The server has answered once in this process. */
        var confirmed = false
    }

    private val cache = AtomicReference<Map<String, State>>(emptyMap())
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = true
            encodeDefaults = true
        }

    internal fun now(): Long =
        kotlin.time.Clock.System
            .now()
            .epochSeconds

    private fun slot(key: String): State {
        while (true) {
            val cur = cache.value
            cur[key]?.let { return it }
            val made = State()
            if (cache.compareAndSet(cur, cur + (key to made))) return made
        }
    }

    /** Test seam: what a fresh process would hold for [root] (nothing). */
    internal fun forget(root: String) {
        while (true) {
            val cur = cache.value
            if (cache.compareAndSet(cur, cur - Index.canon(root))) return
        }
    }

    private fun mirrorKey(key: String) = "capability:$key"

    internal fun writeMirror(
        cx: Ctx,
        key: String,
        rec: CapabilityRecord,
    ) {
        // A lost write only means another process probes for itself.
        runCatching { cx.pluginStateSet("graph", mirrorKey(key), encode(rec)) }
    }

    /** Serde's shape: `down` is skipped while false. */
    internal fun encode(rec: CapabilityRecord): String {
        val obj = json.encodeToJsonElement(CapabilityRecord.serializer(), rec) as kotlinx.serialization.json.JsonObject
        return (if (rec.down) obj else kotlinx.serialization.json.JsonObject(obj - "down")).toString()
    }

    private fun readMirror(
        cx: Ctx,
        key: String,
    ): CapabilityRecord? = runCatching { cx.pluginStateGet("graph", mirrorKey(key))?.let { json.decodeFromString(CapabilityRecord.serializer(), it) } }.getOrNull()

    /** The record some process stored for [root]; what the CLI and the page show. */
    fun mirrored(
        cx: Ctx,
        root: String,
    ): CapabilityRecord? = readMirror(cx, Index.canon(root))

    /** One line, at most 120 chars; the first line of an error is the useful one. */
    fun clip(text: String): String =
        text.lineSequence().firstOrNull().orEmpty().let { l ->
            var n = 0
            var i = 0
            while (i < l.length && n < 120) {
                i += if (l[i].isHighSurrogate() && i + 1 < l.length) 2 else 1
                n++
            }
            l.substring(0, i)
        }

    fun reasonOf(e: Throwable): String = clip(e.message ?: e.toString())

    fun serverBroke(e: Throwable): Boolean = e !is Refused

    private fun measure(
        root: String,
        config: String,
        probe: Probe,
    ): CapabilityRecord {
        val absent = probe(root)
        val at = now()
        val backend = if (absent == null) Chosen.Lsp else Chosen.Tags
        return CapabilityRecord(
            backend = backend,
            language = Langs.languageOf(root),
            server = absent?.server ?: true,
            reason = absent?.let { clip(it.reason) },
            config = config,
            checkedAt = at,
            nextProbeAt = if (backend == Chosen.Tags) at + RECHECK_S else null,
        )
    }

    /** The held record for [config], else a peer's record not yet due, else a fresh check. Caller holds the lock. */
    private fun State.current(
        cx: Ctx,
        key: String,
        root: String,
        config: String,
        probe: Probe,
    ): CapabilityRecord {
        rec?.takeIf { it.config == config }?.let { return it }
        confirmed = false
        val adopted = readMirror(cx, key)?.takeIf { m -> m.config == config && (m.nextProbeAt == null || now() < m.nextProbeAt) }
        val fresh = adopted ?: measure(root, config, probe).also { writeMirror(cx, key, it) }
        rec = fresh
        return fresh
    }

    /** A server that was working stops being asked, once, with the reason. Caller holds the lock. */
    private fun State.downgrade(
        cx: Ctx,
        key: String,
        reason: String,
    ) {
        val r = rec?.takeIf { it.backend == Chosen.Lsp } ?: return
        val at = now()
        val down = r.copy(backend = Chosen.Tags, reason = reason, checkedAt = at, nextProbeAt = at + RECHECK_S, down = true)
        rec = down
        writeMirror(cx, key, down)
    }

    /**
     * One request through the record. The first server call of a process runs under the project's lock, so
     * concurrent first requests share its outcome; a server that breaks (anything but [Refused]) is downgraded.
     */
    fun ask(
        cx: Ctx,
        root: String,
        config: String,
        probe: Probe,
        server: () -> String,
    ): Asked {
        val key = Index.canon(root)
        val st = slot(key)
        st.lock.lock()
        var held = true
        try {
            val rec = st.current(cx, key, root, config, probe)
            if (rec.backend == Chosen.Tags) return Asked.Tags(rec)
            if (st.confirmed) {
                st.lock.unlock()
                held = false
            }
            val answer = runCatching(server)
            if (!held) {
                st.lock.lock()
                held = true
            }
            answer.fold(
                onSuccess = { st.confirmed = true },
                onFailure = { e -> if (serverBroke(e)) st.downgrade(cx, key, reasonOf(e)) },
            )
            return Asked.Lsp(answer)
        } finally {
            if (held) st.lock.unlock()
        }
    }

    /** The server is installed for this project, per the record (a check on first use only). */
    fun serverReady(
        cx: Ctx,
        root: String,
        config: String,
        probe: Probe,
    ): Boolean {
        val key = Index.canon(root)
        val st = slot(key)
        return st.lock.hold { st.current(cx, key, root, config, probe).backend == Chosen.Lsp }
    }

    /** The record this process holds for [root]; the health check retries only these. */
    fun held(root: String): CapabilityRecord? = cache.value[Index.canon(root)]?.let { st -> st.lock.hold { st.rec } }

    /** Checks one project again and replaces its record; only a `backend` change and the health check call it. */
    fun reprobe(
        cx: Ctx,
        root: String,
        config: String,
        probe: Probe,
    ): CapabilityRecord {
        val key = Index.canon(root)
        val st = slot(key)
        return st.lock.hold {
            measure(root, config, probe).also {
                writeMirror(cx, key, it)
                st.rec = it
                st.confirmed = false
            }
        }
    }
}
