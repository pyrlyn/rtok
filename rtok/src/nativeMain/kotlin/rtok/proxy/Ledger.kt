// The bookkeeping half of src/proxy/mod.rs: `record` (session, model, the `calls` row), the one `usage` row every
// request owes (T5.1), `finish` after the body was forwarded and the semantic-cache hit. All fail open: an error
// is logged and the response is never touched.
package rtok.proxy

import kotlinx.serialization.json.JsonElement
import rtok.plugin.sdk.Measurement
import rtok.store.SerdeJson
import rtok.store.hexSha256
import rtok.store.insertCall
import rtok.store.insertCallIo
import rtok.store.insertMeasurement
import rtok.store.insertProviderTokens
import rtok.store.insertUsage
import rtok.store.setCallMs
import rtok.store.setCallServiceTier
import rtok.store.upsertModel
import rtok.store.upsertSession

/** One recorded request: its session, the model it named and its `calls` row. */
class Recorded(
    val session: String,
    val model: String?,
    val callId: Int,
)

/** What `finish` sees of one forwarded call. */
class Exchange(
    val wire: Wire?,
    val status: Int,
    val contentType: String?,
    val request: ByteArray,
    /** The retained prefix of the response (capped at [ProxyHttp.MAX_BODY_BYTES]). */
    val response: ByteArray,
    val totalBytes: Long,
    /** False when the stream was cut short or capped: such a body is recorded but never cached (T323). */
    val complete: Boolean,
    /** The cache scope the lookup used; null when the lane does not use the cache (T385.2). */
    val caller: String?,
    /** The Batch results source and its scope (T385.4, T583), when asked for. */
    val results: Pair<BatchResults.Source, String>?,
)

object Ledger {
    private const val BYTES_PER_TOKEN = 4

    private fun ProxyState.err(
        r: Recorded,
        what: String,
        e: Throwable,
    ) = log(r.session, r.callId, "error", "$what: ${e.message}")

    /** The request's `model` field, when the body is JSON and names one. */
    fun requestModel(body: ByteArray): String? = runCatching { SerdeJson.parse(body.decodeToString()) }.getOrNull().str("model")

    /** The session: the wire's body field, else `x-rtok-session` / `x-session-id`, else the body's sha256. */
    fun sessionFor(
        wire: Wire?,
        body: JsonElement?,
        headers: List<Pair<String, String>>,
        raw: ByteArray,
    ): String {
        wire?.let { w -> body?.let(w::sessionId) }?.let { return it }
        val named =
            listOf("x-rtok-session", "x-session-id").firstNotNullOfOrNull { n ->
                headers.firstOrNull { it.first.equals(n, ignoreCase = true) && it.second.isNotEmpty() }?.second
            }
        return named ?: hexSha256(raw)
    }

    /** The `calls` row of one request; [kind] is the lane's, [providerOverride] a routed call's (T385.10). */
    fun record(
        state: ProxyState,
        wire: Wire?,
        path: String,
        body: JsonElement?,
        headers: List<Pair<String, String>>,
        kind: String,
        providerOverride: String?,
        raw: ByteArray,
    ): Recorded? {
        val session = sessionFor(wire, body, headers, raw)
        val model = wire?.model(path, body) ?: body.str("model")
        val provider = providerOverride ?: wire?.provider ?: Wires.OPENAI_PROVIDER.takeIf { path == "/v1/chat/completions" || path == "/v1/responses" }
        return runCatching {
            val s = state.store
            s.upsertSession(session, state.hostId, null, null, "proxy")
            val ids = if (provider != null && model != null) s.upsertModel(provider, model) else null
            Recorded(session, model, s.insertCall(session, "proxy", kind, state.hostId, ids?.first, ids?.second, null, path))
        }.onFailure { state.log(session, null, "error", "record $path: ${it.message}") }.getOrNull()
    }

    /** The one `usage` row (zeros when [counters] is null) plus, with counters, the provider `tokens` row. */
    fun usage(
        state: ProxyState,
        r: Recorded,
        wire: Wire,
        counters: Usage?,
    ) {
        val u = counters ?: Usage()
        runCatching { state.store.insertUsage(r.session, r.model, wire.api, u.input, u.cacheCreate, u.cacheRead, u.output, r.callId) }
            .onFailure { state.err(r, "usage", it) }
        if (counters != null) {
            runCatching { state.store.insertProviderTokens(r.callId, wire.providerTotal(u), u.input, u.cacheCreate, u.cacheRead, u.output) }
                .onFailure { state.err(r, "tokens", it) }
        }
    }

    /** A zero-delta marker row (`context_management`, `model_route`): both sizes the body's, no saving claimed. */
    fun marker(
        state: ProxyState,
        r: Recorded,
        kind: String,
        nbytes: Int,
    ) {
        val est = maxOf(nbytes / BYTES_PER_TOKEN, 1).toUInt()
        val m = Measurement("proxy", kind, nbytes.toULong(), nbytes.toULong(), est, est, null, r.callId)
        runCatching { state.store.insertMeasurement(r.session, m) }.onFailure { state.err(r, kind.replace('_', ' '), it) }
    }

    fun measurement(
        state: ProxyState,
        r: Recorded,
        m: Measurement,
        what: String,
    ) {
        runCatching { state.store.insertMeasurement(r.session, m) }.onFailure { state.err(r, what, it) }
    }

    /** After the body was forwarded: `calls.ms`, tier, `call_io`, the cache, then the usage rows. */
    suspend fun finish(
        state: ProxyState,
        r: Recorded,
        ms: Double,
        x: Exchange,
    ) {
        runCatching { state.store.setCallMs(r.callId, ms) }.onFailure { state.err(r, "set_call_ms", it) }
        val tier = x.wire?.let { Responses.serviceTier(x.contentType, x.response) }
        if (tier != null) runCatching { state.store.setCallServiceTier(r.callId, tier) }.onFailure { state.err(r, "service_tier", it) }
        if (x.totalBytes > x.response.size) {
            state.log(
                r.session,
                r.callId,
                "error",
                "response body truncated for recording: ${x.totalBytes} bytes received, ${x.response.size} retained (MAX_BODY_BYTES); " +
                    "call_io and usage reflect only the retained bytes",
            )
        }
        runCatching { state.store.insertCallIo(r.callId, x.request, x.response, state.inlineCap, state.archiveDir) }.onFailure { state.err(r, "call_io", it) }
        // Fill the cache before the usage rows: a visible usage row then implies a warm cache.
        if (x.complete && x.caller != null) cachePrompt(state, x.wire, x.request, x.caller)?.let { state.fill(it, x.response, x.contentType, x.status) }
        usageOf(state, r, x)
    }

    private fun usageOf(
        state: ProxyState,
        r: Recorded,
        x: Exchange,
    ) {
        val wire = x.wire
        val counters = wire?.let { Responses.usage(it, x.contentType, x.response) }
        when {
            wire != null -> {
                usage(state, r, wire, counters)
                if (counters == null) state.log(r.session, r.callId, "info", "no usage in upstream response")
            }

            x.results != null -> {
                val (source, scope) = x.results
                runCatching { BatchResults.record(state.store, r.session, r.callId, r.model, source, scope, x.response) }
                    .onFailure { state.err(r, "batch results", it) }
            }
        }
    }

    /** The cache key of [request], when the cache is on and the body is eligible. */
    fun cachePrompt(
        state: ProxyState,
        wire: Wire?,
        request: ByteArray,
        caller: String,
    ): CachePrompt? {
        val sc = state.cfg.plugins.proxy.semanticCache
        val body = if (sc.enabled && wire != null) SerdeJson.parse(request.decodeToString()) else null
        return if (body != null && wire != null && CacheKey.eligible(body, sc)) CacheKey.buildPrompt(wire, body, sc)?.withCaller(caller) else null
    }

    /** A semantic-cache hit: `finish`'s bookkeeping without the round trip, then the cached body. */
    fun cacheHit(
        state: ProxyState,
        wire: Wire,
        r: Recorded?,
        request: ByteArray,
        ms: Double,
        hit: CacheHit,
    ): rtok.http.server.StreamingResponse {
        if (r != null) {
            measurement(state, r, ResponseCache.measurement(hit, r.callId, hit.response.size), "cache hit")
            runCatching { state.store.setCallMs(r.callId, ms) }
            runCatching { state.store.insertCallIo(r.callId, request, hit.response, state.inlineCap, state.archiveDir) }
            usage(state, r, wire, Responses.usage(wire, hit.contentType, hit.response))
        }
        return ProxyHttp.bytes(hit.status, hit.contentType, hit.response)
    }
}
