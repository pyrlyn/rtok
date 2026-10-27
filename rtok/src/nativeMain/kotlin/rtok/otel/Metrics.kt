// Port of src/otel/metrics.rs: the cumulative sums (tokens billed, tokens saved, calls) from the ledgers.
package rtok.otel

import rtok.store.Store
import rtok.store.otelCallTotals
import rtok.store.otelFirstTs
import rtok.store.otelSavedTotals
import rtok.store.otelTokenTotals

/** `gen_ai.provider.name` from the wire's `api` slug (`openai_chat` → `openai`). */
internal fun provider(api: String): String = if (api.startsWith("openai")) "openai" else api

fun sums(
    store: Store,
    nowNs: Long,
): List<Sum> {
    val startNs = secondsToNs(store.otelFirstTs())

    fun sum(
        name: String,
        unit: String,
        description: String,
        monotonic: Boolean,
        points: List<Pair<List<Attr>, Long>>,
    ) = Sum(name, unit, description, monotonic, startNs, nowNs, points)
    val tokens =
        store.otelTokenTotals().flatMap { t ->
            val model = t.model ?: "unknown"
            listOf("input" to t.input, "output" to t.output, "cache_read" to t.cacheRead, "cache_creation" to t.cacheCreate).map { (kind, n) ->
                listOf(s("gen_ai.token.type", kind), s("gen_ai.request.model", model), s("gen_ai.provider.name", provider(t.api)), s("rtok.api", t.api)) to n
            }
        }
    val saved = store.otelSavedTotals().map { listOf(s("rtok.plugin", it.plugin), s("rtok.kind", it.kind)) to it.saved }
    val calls = store.otelCallTotals().map { listOf(s("rtok.surface", it.surface), s("rtok.kind", it.kind), b("rtok.ok", it.ok != 0)) to it.n }
    return listOf(
        sum("rtok.tokens", "{token}", "Tokens billed by the provider, from the proxy's usage ledger (semantic-cache hits excluded)", true, tokens),
        sum("rtok.tokens.saved", "{token}", "Estimated tokens removed by rtok plugins (est_before − est_after)", false, saved),
        sum("rtok.calls", "{call}", "Hook, MCP and proxied calls recorded", true, calls),
    )
}
