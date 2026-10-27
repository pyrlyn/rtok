// Port of `app`/`handle` in src/proxy/mod.rs and the routes of src/proxy/cli.rs: `/health`, `/live` (loopback
// only) and a fallback that forwards every other request upstream. Requests run concurrently; SSE streams
// through unchanged while a capped copy is tee'd for the ledger, which is written after the stream ends.
package rtok.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.http.server.ServerRequest
import rtok.http.server.StreamingHandler
import rtok.http.server.StreamingResponse
import rtok.store.SerdeJson
import rtok.store.setCallMs
import kotlin.time.TimeSource

class ProxyHandler(
    private val state: ProxyState,
) : StreamingHandler {
    override suspend fun handle(
        req: ServerRequest,
        peer: String?,
    ): StreamingResponse =
        when {
            req.path == "/health" || req.path == "/live" -> {
                route(req, peer)
            }

            else -> {
                ProxyCall(state, req).run()
            }
        }

    private fun route(
        req: ServerRequest,
        peer: String?,
    ): StreamingResponse =
        when {
            req.method != "GET" -> ProxyHttp.bytes(ProxyHttp.METHOD_NOT_ALLOWED, null, ByteArray(0))
            req.path == "/health" -> json(health(state))
            !ProxyHttp.peerIsLocal(peer) -> ProxyHttp.bytes(ProxyHttp.FORBIDDEN, null, ByteArray(0))
            else -> json(Json.encodeToString(ListSerializer(LiveCall.serializer()), Live.ring.snapshot()))
        }

    private fun json(body: String) = ProxyHttp.bytes(ProxyHttp.OK, "application/json", body.encodeToByteArray())

    companion object {
        /** `GET /health`: plain mode says so (listener up, nothing recorded). */
        fun health(state: ProxyState): String =
            SerdeJson.encode(
                if (state.plain()) {
                    JsonObject(
                        mapOf(
                            "ok" to JsonPrimitive(true),
                            "mode" to JsonPrimitive("passthrough"),
                            "enabled" to JsonPrimitive(false),
                            "recording" to JsonPrimitive(false),
                            "live" to JsonPrimitive(Live.ring.snapshot().size),
                        ),
                    )
                } else {
                    JsonObject(mapOf("ok" to JsonPrimitive(true), "mode" to JsonPrimitive(state.mode), "enabled" to JsonPrimitive(true), "recording" to JsonPrimitive(true)))
                },
            )
    }
}

/** One forwarded request, from lane classification to the ledger. */
private class ProxyCall(
    private val state: ProxyState,
    private val req: ServerRequest,
) {
    private val start = TimeSource.Monotonic.markNow()
    private val cfg = state.cfg
    private val method = req.method
    private var headers = req.headers.flatMap { (k, vs) -> vs.map { k to it } }
    private val lane: Lane
    private val path: String

    init {
        // The lane marker is for rtok, not the provider; with lanes off both pass through untouched.
        if (cfg.proxy.lanes.enabled) {
            val c = Lane.classify(req.path, req.header(ProxyHttp.LANE_HEADER))
            headers = headers.filterNot { it.first.equals(ProxyHttp.LANE_HEADER, ignoreCase = true) }
            lane = c.lane
            path = c.path
        } else {
            lane = Lane.Agent
            path = req.path
        }
    }

    private fun ms() = start.elapsedNow().inWholeMicroseconds / MICROS_PER_MS

    private suspend fun offWorker(block: suspend () -> Unit) {
        withContext(Dispatchers.IO) { runCatching { block() } }
    }

    suspend fun run(): StreamingResponse {
        val hops = ProxyHttp.hopsOf(headers)
        headers = headers.filterNot { it.first.equals(ProxyHttp.HOP_HEADER, ignoreCase = true) }
        loop(hops)?.let { return it }
        // The lane's in-flight cap (T385.7); plain mode never turns a request away.
        val gate = state.gates[lane]?.takeUnless { state.plain() }
        val slot = gate?.enter()
        val full = gate != null && slot == null
        val msg = "$method $path: ${lane.laneName} lane at max_in_flight with a full queue, answered 429"
        if (full) offWorker { state.log("?", null, "warn", msg) }
        var handedOff = false
        return if (full) {
            ProxyHttp.queueFull(msg)
        } else {
            try {
                forward(hops, slot).also { handedOff = it.second }.first
            } finally {
                if (!handedOff) slot?.release()
            }
        }
    }

    /** 508 for a chain at [ProxyHttp.MAX_HOPS]; a later pass is only logged. */
    private suspend fun loop(hops: Int): StreamingResponse? {
        if (hops > 0 && hops < ProxyHttp.MAX_HOPS) offWorker { state.log("?", null, "warn", "$method $path: already through $hops rtok proxy hop(s), forwarding as is") }
        if (hops < ProxyHttp.MAX_HOPS) return null
        val msg = "$method $path: $hops rtok proxy hops, refusing a loop (check proxy.upstream)"
        offWorker { state.log("?", null, "error", msg) }
        return ProxyHttp.error(ProxyHttp.LOOP_DETECTED, msg)
    }

    /** The response and whether the slot now rides with the upstream stream. */
    private suspend fun forward(
        hops: Int,
        slot: Gate.Slot?,
    ): Pair<StreamingResponse, Boolean> {
        val wire = Wires.forPath(path)
        // Plain mode, or an earlier rtok hop already shaped and recorded this request (T442).
        val plain = state.plain() || hops > 0
        val shaped =
            if (plain) {
                Shaped(req.body)
            } else {
                withContext(Dispatchers.IO) { runCatching { Shape.request(state, wire, path, headers, lane, req.body) }.getOrElse { Shaped(req.body) } }
            }
        // Resolved before the lookup (T533): an answer cached for one upstream is never served for another.
        val target =
            runCatching { Upstream.join(state.upstreamFor(lane, wire, path, headers), path, req.query) }
                .getOrElse { return ProxyHttp.error(ProxyHttp.BAD_REQUEST, it.message ?: "bad upstream") to false }
        val cacheLane = lane.policy(cfg.proxy.lanes).semanticCache
        val caller = CacheKey.cacheScope(headers, method, target)
        val hit = if (cacheLane && !plain && wire != null) cached(wire, shaped, caller) else null
        val results =
            BatchResults
                .source(lane, method, path)
                .takeIf { cfg.proxy.batch.parseResults }
                ?.let { it to target.substringBefore('?') }
        return hit?.let { it to false } ?: (Call(wire, plain, shaped, target, if (cacheLane) caller else null, results).open(outgoing(hops, shaped.contextArmed), slot) to true)
    }

    /** A semantic-cache hit answered without the round trip, or null. */
    private suspend fun cached(
        wire: Wire,
        shaped: Shaped,
        caller: String,
    ): StreamingResponse? {
        val hit = Ledger.cachePrompt(state, wire, shaped.body, caller)?.let { state.lookup(it) } ?: return null
        return withContext(Dispatchers.IO) { Ledger.cacheHit(state, wire, shaped.recorded, shaped.body, ms(), hit) }
    }

    /** The forwarded headers: no hop-by-hop, `identity` encoding only (the tee must stay readable), the hop count. */
    private fun outgoing(
        hops: Int,
        armed: Boolean,
    ): List<Pair<String, String>> {
        val out = headers.filterNot { ProxyHttp.hopByHop(it.first) || it.first.equals("accept-encoding", ignoreCase = true) }.toMutableList()
        out += "accept-encoding" to "identity"
        out += ProxyHttp.HOP_HEADER to (hops + 1).toString()
        val beta = headers.any { it.first.equals("anthropic-beta", ignoreCase = true) && AnthropicWire.CONTEXT_BETA in it.second }
        if (armed && !beta) out += "anthropic-beta" to AnthropicWire.CONTEXT_BETA
        return out
    }

    private sealed interface Head {
        class Open(
            val status: Int,
            val headers: List<Pair<String, String>>,
            val body: Channel<ByteArray>,
        ) : Head

        class Failed(
            val message: String,
        ) : Head
    }

    private inner class Call(
        val wire: Wire?,
        val plain: Boolean,
        val shaped: Shaped,
        val target: String,
        val caller: String?,
        val results: Pair<BatchResults.Source, String>?,
    ) {
        /** Start the upstream call on the state's scope and answer as soon as its status line is in. */
        suspend fun open(
            out: List<Pair<String, String>>,
            slot: Gate.Slot?,
        ): StreamingResponse {
            val head = CompletableDeferred<Head>()
            val job =
                state.scope.launch {
                    var contentType: String? = null
                    var status = 0
                    val sent =
                        try {
                            Forward.send(state.clientFor(lane), method, target, out, shaped.body, shaped.flexRetry) { r ->
                                status = r.status
                                contentType = r.header("content-type")
                                val ch = Channel<ByteArray>(TEE_CHANNEL_CHUNKS)
                                head.complete(Head.Open(r.status, r.headers.filterNot { ProxyHttp.hopByHop(it.first) }, ch))
                                Forward.tee(r, ch, ProxyHttp.MAX_BODY_BYTES)
                            }
                        } finally {
                            // Upstream is done with this request; bookkeeping needs no slot.
                            slot?.release()
                        }
                    if (sent.retries > 0u) offWorker { state.log(shaped.recorded?.session ?: "?", shaped.recorded?.callId, "warn", "flex 429 on $method $path: ${sent.retries} retry(ies)") }
                    val tee = sent.tee
                    if (tee == null) {
                        val msg = scrub(sent.error?.message ?: "request failed")
                        offWorker { failed(sent.body, msg) }
                        head.complete(Head.Failed(msg))
                    } else {
                        offWorker { done(sent.body, status, contentType, tee) }
                    }
                }
            // Whatever ends the call, the handler is never left waiting for a status line.
            job.invokeOnCompletion { head.complete(Head.Failed(it?.message ?: "upstream call ended")) }
            return when (val h = head.await()) {
                is Head.Failed -> {
                    ProxyHttp.error(ProxyHttp.BAD_GATEWAY, "rtok proxy: upstream error: ${h.message}")
                }

                is Head.Open -> {
                    StreamingResponse(h.status, h.headers) { sink ->
                        runCatching { for (chunk in h.body) sink.write(chunk) }.onFailure {
                            h.body.cancel()
                            throw it
                        }
                    }
                }
            }
        }

        /** A transport error without its URL query: Gemini callers put the API key in `?key=`. */
        private fun scrub(message: String) = message.replace(target, target.substringBefore('?')).replace(Regex("""\?[^\s"']*"""), "")

        private fun live(
            status: Int,
            requestBytes: Int,
            responseBytes: Long,
        ) = Live.ring.push(
            LiveCall(
                rtok.log.now(),
                method,
                path,
                wire?.provider,
                Ledger.requestModel(shaped.body),
                status,
                requestBytes.toLong(),
                responseBytes,
                ms(),
            ),
        )

        private fun failed(
            body: ByteArray,
            message: String,
        ) {
            val r = shaped.recorded
            when {
                plain -> {
                    live(ProxyHttp.BAD_GATEWAY, body.size, 0)
                }

                else -> {
                    r?.let { runCatching { state.store.setCallMs(it.callId, ms()) } }
                    // Every request owes one usage row (T5.1): with no response there are no counters.
                    if (wire != null && r != null) Ledger.usage(state, r, wire, null)
                    state.log(r?.session ?: "?", r?.callId, "error", "upstream $method $path: $message")
                }
            }
        }

        private suspend fun done(
            body: ByteArray,
            status: Int,
            contentType: String?,
            tee: Tee,
        ) {
            val r = shaped.recorded
            when {
                plain -> {
                    live(status, body.size, tee.totalBytes)
                }

                r != null -> {
                    val res = results.takeIf { ProxyHttp.isSuccess(status) }
                    Ledger.finish(state, r, ms(), Exchange(wire, status, contentType, body, tee.buffer, tee.totalBytes, tee.complete, caller, res))
                }
            }
        }
    }

    private companion object {
        /** Upstream chunks buffered between the tee and the client before the tee waits for the client. */
        const val TEE_CHANNEL_CHUNKS = 32
        const val MICROS_PER_MS = 1000.0
    }
}
