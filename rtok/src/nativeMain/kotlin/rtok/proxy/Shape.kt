// Port of `shape_request` and its rewrites in src/proxy/mod.rs: route, record, compress (with noise), provider
// shaping, context edits, tools rewrite and Flex, each only when its global switch and the lane's are both on.
// Every step fails open: an unparseable or unchanged body is forwarded exactly as it arrived.
package rtok.proxy

import kotlinx.serialization.json.JsonElement
import rtok.config.LanePolicy
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.Measurement
import rtok.plugin.sdk.WireRequest
import rtok.plugins.compress.Auth
import rtok.plugins.compress.takeOffered
import rtok.store.SerdeJson
import rtok.store.putArchive

/** The body to forward and what shaping learned on the way. */
class Shaped(
    val body: ByteArray,
    val recorded: Recorded? = null,
    val contextArmed: Boolean = false,
    val flexRetry: Flex.Retry? = null,
)

object Shape {
    private fun parse(body: ByteArray): JsonElement? = runCatching { SerdeJson.parse(body.decodeToString()) }.getOrNull()

    private fun encode(body: JsonElement) = SerdeJson.encode(body).encodeToByteArray()

    /** One non-plain request's bookkeeping and rewrites, in the order the Rust proxy runs them. */
    fun request(
        state: ProxyState,
        wire: Wire?,
        path: String,
        headers: List<Pair<String, String>>,
        lane: Lane,
        original: ByteArray,
    ): Shaped {
        val policy = lane.policy(state.cfg.proxy.lanes)
        val (routed, parsed, recorded) = routeAndRecord(state, wire, path, headers, lane, original)
        val compressed = if (state.mode == "compress" && policy.compress) compressStep(state, wire, headers, parsed, recorded, policy, routed) else routed
        val (body, armed) = providerShaping(state, wire, lane, policy, recorded, compressed)
        // Last, so the tier is the only thing that differs from what the other rewrites left.
        val flexed = if (wire != null && policy.flex) Flex.apply(wire.provider, body, state.cfg.proxy.flex) else null
        return Shaped(flexed ?: body, recorded, armed, flexed?.let { Flex.Retry.of(body, state.cfg.proxy.flex) })
    }

    /** The routed body (T385.10, before `record` so the ledger names the serving model), parsed, and recorded. */
    private fun routeAndRecord(
        state: ProxyState,
        wire: Wire?,
        path: String,
        headers: List<Pair<String, String>>,
        lane: Lane,
        original: ByteArray,
    ): Triple<ByteArray, JsonElement?, Recorded?> {
        val route = lane.route(state.cfg.proxy.routing)?.let { Route.apply(it, original) }
        val routed = route?.body ?: original
        val parsed = parse(routed)
        val recorded = Ledger.record(state, wire, path, parsed, headers, lane.kind, route?.provider, routed)
        if (route != null && recorded != null) {
            Ledger.marker(state, recorded, "model_route", route.body.size)
            state.log(recorded.session, recorded.callId, "info", "model route on the ${lane.laneName} lane: ${route.from} -> ${route.to}")
        }
        return Triple(routed, parsed, recorded)
    }

    /** Provider shaping (T11.2), context edits (T51.2) and the tools rewrite (T59.5); whether edits were armed. */
    private fun providerShaping(
        state: ProxyState,
        wire: Wire?,
        lane: Lane,
        policy: LanePolicy,
        recorded: Recorded?,
        body: ByteArray,
    ): Pair<ByteArray, Boolean> {
        val prepared = if (wire != null && !lane.passesThrough) prepare(state, wire, body) else body
        val edited = if (wire != null && policy.contextManagement) contextEdits(state, wire, prepared) else null
        if (edited != null && recorded != null) Ledger.marker(state, recorded, "context_management", edited.size)
        val out = edited ?: prepared
        return (if (policy.toolsRewrite) rewriteTools(state, recorded, out) else out) to (edited != null)
    }

    /** `compress` mode: the plugins, the summary queue, then terminal noise (T432). */
    private fun compressStep(
        state: ProxyState,
        wire: Wire?,
        headers: List<Pair<String, String>>,
        parsed: JsonElement?,
        recorded: Recorded?,
        policy: LanePolicy,
        body: ByteArray,
    ): ByteArray {
        val compressed = wire?.let { compress(state, it, parsed, recorded, policy, body) } ?: body
        // Drained on every pass so the next request starts empty.
        val offered = takeOffered()
        if (offered.isNotEmpty()) state.summaries?.submit(Auth.fromHeaders(headers, wire?.provider), offered)
        return wire?.let { Noise.strip(it, compressed) } ?: compressed
    }

    /** The wire's own request shaping (T11.2: OpenAI `stream_options`). */
    private fun prepare(
        state: ProxyState,
        wire: Wire,
        original: ByteArray,
    ): ByteArray = parse(original)?.let { wire.prepareRequest(it, state.cfg.proxy.includeUsage) }?.let(::encode) ?: original

    /** The platform path (T51.2): server-side clearing on the Anthropic wire; the new body, or null. */
    private fun contextEdits(
        state: ProxyState,
        wire: Wire,
        original: ByteArray,
    ): ByteArray? =
        if (wire.api != Wires.API_ANTHROPIC) {
            null
        } else {
            parse(original)?.let { AnthropicWire.applyContextEdits(it, state.cfg.proxy.contextManagement) }?.let(::encode)
        }

    /** T59.5: truncate / drop `tools[]`; the saving is recorded with the original catalog archived (T686). */
    private fun rewriteTools(
        state: ProxyState,
        recorded: Recorded?,
        original: ByteArray,
    ): ByteArray {
        val tr = state.cfg.proxy.toolsRewrite
        val result = if (tr.enabled) parse(original)?.let { ToolsRewriter.rewrite(it, tr, state.cfg.estimator) } else null
        val d = result?.delta ?: return original
        recorded?.let { r ->
            val ref =
                d.original?.let { tools ->
                    state.archiveDir?.let { dir ->
                        runCatching { state.store.putArchive(r.session, tools.encodeToByteArray(), dir) }
                            .onFailure { state.log(r.session, r.callId, "error", "tools rewrite archive: ${it.message}") }
                            .getOrNull()
                    }
                }
            val m = Measurement("proxy", "tools_rewrite", d.beforeBytes.toULong(), d.afterBytes.toULong(), d.estBefore.toUInt(), d.estAfter.toUInt(), ref, r.callId)
            Ledger.measurement(state, r, m, "tools rewrite")
        }
        return if (d.changed) encode(result.body) else original
    }

    /** `compress` mode: every enabled plugin's `proxy_filter` over the parsed body (T5.3). */
    private fun compress(
        state: ProxyState,
        wire: Wire,
        parsed: JsonElement?,
        recorded: Recorded?,
        policy: LanePolicy,
        original: ByteArray,
    ): ByteArray {
        val cx =
            if (parsed != null && recorded != null) {
                runCatching { Runtime.open(state.cfg, recorded.session) }
                    .onFailure { state.log(recorded.session, recorded.callId, "error", "ctx: ${it.message}") }
                    .getOrNull()
            } else {
                null
            }
        return if (cx == null || parsed == null || recorded == null) original else cx.use { filter(state, it, wire, parsed, recorded, policy) ?: original }
    }

    /** The plugins' rewrites on [parsed]; null when none changed it. */
    private fun filter(
        state: ProxyState,
        cx: Runtime,
        wire: Wire,
        parsed: JsonElement,
        recorded: Recorded,
        policy: LanePolicy,
    ): ByteArray? {
        cx.callId = recorded.callId
        // With context edits armed the platform clears old tool uses, so `archive` stands down (T51.2).
        val platformClears = wire.api == Wires.API_ANTHROPIC && state.cfg.proxy.contextManagement && policy.contextManagement
        val skipped = { id: String -> if (id == "archive") platformClears else id == "toon" && !policy.toon }
        val req = WireRequest(wire, parsed)
        var changed = false
        for (p in state.registry.enabled().filterNot { skipped(it.manifest().id) }) {
            for (m in p.proxyFilter(req, Ctx(cx))) {
                changed = true
                runCatching { cx.record(m) }.onFailure { state.log(recorded.session, recorded.callId, "error", "measurement: ${it.message}") }
            }
        }
        return if (changed) encode(req.body) else null
    }
}
