// Port of `ProxyState` in src/proxy/mod.rs: the store, the upstream clients and the effective `[proxy]` settings
// one proxy process serves with.
package rtok.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rtok.config.Config
import rtok.http.HttpSettings
import rtok.http.StreamingHttpClient
import rtok.http.ktor.KtorHttp
import rtok.plugins.Registry
import rtok.plugins.compress.Queue
import rtok.recordLog
import rtok.store.Store
import rtok.store.hostId
import rtok.store.setStoreRaw

class ProxyState(
    val cfg: Config,
    val store: Store,
    http: (HttpSettings) -> StreamingHttpClient = { KtorHttp(it) },
) : AutoCloseable {
    private val client = http(settings(cfg.proxy.timeoutS))

    /** Lanes with their own read timeout (T385.2); the rest use [client]. */
    private val laneClients: Map<Lane, StreamingHttpClient>

    /** Lanes sent to their own `[proxy.lanes.<lane>] upstream` (T385.7). */
    private val laneUpstreams: Map<Lane, String>

    /** Lanes with a `max_in_flight` cap; the rest go straight on. */
    internal val gates: Map<Lane, Gate>
    private val upstream = cfg.proxy.upstream.trimEnd('/')
    private val openaiUpstream = cfg.proxy.openaiUpstream.trimEnd('/')
    private val geminiUpstream = cfg.proxy.geminiUpstream.trimEnd('/')

    /** The `[hook] host` agent; unknown slugs are `other` (6). */
    val hostId: Int? = store.hostId(cfg.hook.host) ?: OTHER_HOST
    val inlineCap = cfg.core.callIoInlineBytes.toInt()
    val archiveDir: String? = cfg.core.archiveDir
    val mode = cfg.proxy.mode
    val registry = Registry.new(cfg)
    private val cache = ResponseCache()
    private val cacheLock = Mutex()

    /** P28 model summaries (T385.9.2): null unless `[plugins.compress.llm] enabled`. */
    val summaries: Queue?

    /** Where the tee and bookkeeping of each forwarded call run; they outlive the handler that started them. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        val clients = mutableMapOf<Lane, StreamingHttpClient>()
        val ups = mutableMapOf<Lane, String>()
        val gs = mutableMapOf<Lane, Gate>()
        for (lane in Lane.entries) {
            val policy = lane.policy(cfg.proxy.lanes)
            val secs = Flex.laneTimeoutS(policy.flex, policy.timeoutS, cfg.proxy.timeoutS)
            if (secs > 0u && secs != cfg.proxy.timeoutS) clients[lane] = http(settings(secs))
            policy.upstream
                .trim()
                .trimEnd('/')
                .takeIf { it.isNotEmpty() }
                ?.let { ups[lane] = it }
            Gate.of(policy.maxInFlight, policy.maxQueued)?.let { gs[lane] = it }
        }
        laneClients = clients
        laneUpstreams = ups
        gates = gs
        // Optional like every extension: a queue that cannot start leaves the extractive view.
        summaries =
            runCatching { Queue.create(cfg) }.getOrElse {
                recordLog(cfg, store, null, null, "warn", "plugin", "compress", "summary queue not started: ${it.message}")
                null
            }
    }

    fun clientFor(lane: Lane): StreamingHttpClient = laneClients[lane] ?: client

    /** Requests on [lane] waiting for an upstream slot right now; 0 when uncapped. */
    fun queued(lane: Lane): Int = gates[lane]?.waiting() ?: 0

    /** The lane's own upstream, else the one owning [wire]; OpenAI Batch/Files paths go to OpenAI. */
    fun upstreamFor(
        lane: Lane,
        wire: Wire?,
        path: String,
        headers: List<Pair<String, String>>,
    ): String =
        laneUpstreams[lane] ?: when {
            wire?.provider == Wires.OPENAI_PROVIDER -> openaiUpstream
            wire?.provider == Wires.GEMINI_PROVIDER -> geminiUpstream
            wire == null && ProxyHttp.isOpenaiBatchPath(path, headers) -> openaiUpstream
            else -> upstream
        }

    /** Any of `proxy.enabled`, `core.enabled`, `plugins.proxy.enabled` false: byte-forward only, nothing recorded. */
    fun plain(): Boolean = !cfg.proxy.enabled || !cfg.core.enabled || !cfg.plugins.proxy.enabled

    suspend fun lookup(
        prompt: CachePrompt,
    ): CacheHit? = cacheLock.withLock { cache.lookup(prompt, cfg.plugins.proxy.semanticCache) }

    suspend fun fill(
        prompt: CachePrompt,
        response: ByteArray,
        contentType: String?,
        status: Int,
    ) = cacheLock.withLock { cache.store(prompt, cfg.plugins.proxy.semanticCache, response, contentType, status) }

    /** One proxy log line through the shared funnel (file + `logs` row). */
    fun log(
        session: String?,
        callId: Int?,
        level: String,
        message: String,
    ) = recordLog(cfg, store, session, callId, level, "module", "proxy", message)

    override fun close() {
        scope.cancel()
        (laneClients.values + client).forEach { runCatching { it.close() } }
        runCatching { store.close() }
    }

    companion object {
        private const val OTHER_HOST = 6
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val MS = 1000uL

        /** Connecting is bounded on its own; the read budget is per socket read, never the whole stream. */
        fun settings(timeoutS: ULong) = HttpSettings(CONNECT_TIMEOUT_MS, Long.MAX_VALUE, (maxOf(timeoutS, 1uL) * MS).toLong())

        fun open(cfg: Config) = ProxyState(cfg, Store.open(cfg.core.dbPath).also { it.setStoreRaw(cfg.core.storeRaw) })
    }
}
