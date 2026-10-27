// Port of the usage half of src/proxy/wire.rs: `Usage`, `UsageFields`, `find_usage`, `int_field`, and the
// JSON/SSE response readers (`usage_from_response`, `service_tier_from_response`).
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.store.SerdeJson

/** Provider usage counters, absent fields as zero. Disjoint: `input` is the uncached prompt only. */
data class Usage(
    val input: Long = 0,
    val cacheCreate: Long = 0,
    val cacheRead: Long = 0,
    val output: Long = 0,
) {
    /** Keep the latest value of every counter carried by a stream event. */
    fun merge(next: Usage) =
        Usage(
            if (next.input != 0L) next.input else input,
            if (next.cacheCreate != 0L) next.cacheCreate else cacheCreate,
            if (next.cacheRead != 0L) next.cacheRead else cacheRead,
            if (next.output != 0L) next.output else output,
        )
}

internal fun Long.satAdd(o: Long): Long {
    val r = this + o
    return when {
        this > 0 && o > 0 && r < 0 -> Long.MAX_VALUE
        this < 0 && o < 0 && r >= 0 -> Long.MIN_VALUE
        else -> r
    }
}

internal fun Long.satSub(o: Long): Long = if (o == Long.MIN_VALUE) (if (this >= 0) Long.MAX_VALUE else this - o) else satAdd(-o)

/** Field names one wire's usage block needs; see the Rust doc on `UsageFields`. */
class UsageFields(
    val altParent: String? = null,
    val container: String = "usage",
    val input: String,
    val output: String,
    val cacheCreate: String? = null,
    val cacheRead: String,
    val cacheReadDetails: String? = null,
    /** The input counter already contains the cached slice, so it is subtracted. */
    val inputIncludesCache: Boolean = false,
    /** A second output counter billed as output (Gemini's `thoughtsTokenCount`). */
    val outputExtra: String? = null,
)

/** An integer counter; absent or non-numeric is zero, whole-number floats count (`10.0`). */
internal fun intField(
    usage: JsonElement?,
    name: String,
): Long {
    val p = (usage as? JsonObject)?.get(name) as? JsonPrimitive ?: return 0
    return if (p.isString) 0 else p.longOrNull ?: p.doubleOrNull?.toLong() ?: 0
}

/** [value]'s usage object (optionally under [UsageFields.altParent]) read through [f]. */
internal fun findUsage(
    value: JsonElement,
    f: UsageFields,
): Usage? {
    val o = value as? JsonObject
    val usage =
        (o?.get(f.container) ?: f.altParent?.let { (o?.get(it) as? JsonObject)?.get(f.container) }) as? JsonObject
            ?: return null
    val cacheRead = if (f.cacheReadDetails != null) intField(usage[f.cacheReadDetails], f.cacheRead) else intField(usage, f.cacheRead)
    val raw = intField(usage, f.input)
    val input = if (f.inputIncludesCache) raw.satSub(cacheRead).coerceAtLeast(0) else raw
    val extra = f.outputExtra?.let { intField(usage, it) } ?: 0
    return Usage(input, f.cacheCreate?.let { intField(usage, it) } ?: 0, cacheRead, intField(usage, f.output).satAdd(extra))
}

object Responses {
    private fun isSse(contentType: String?) = contentType?.contains("text/event-stream") == true

    /**
     * The JSON payloads of an SSE body's `data:` lines, skipping `[DONE]` and unparseable lines. The capture
     * is a byte prefix, so it is decoded lossily (T584): a damaged tail is one unparseable event.
     */
    private fun sseEvents(body: ByteArray): Sequence<JsonElement> =
        body.decodeToString().lineSequence().mapNotNull { line ->
            val data =
                line
                    .removeSuffix("\r")
                    .takeIf { it.startsWith("data:") }
                    ?.removePrefix("data:")
                    ?.trim()
            data?.takeIf { it.isNotEmpty() && it != "[DONE]" }?.let(SerdeJson::parse)
        }

    private fun json(body: ByteArray): JsonElement? = runCatching { body.decodeToString(throwOnInvalidSequence = true) }.getOrNull()?.let(SerdeJson::parse)

    /** Decode JSON or SSE response usage through [wire]. */
    fun usage(
        wire: Wire,
        contentType: String?,
        body: ByteArray,
    ): Usage? =
        if (isSse(contentType)) {
            sseEvents(body).mapNotNull(wire::usageFromSse).fold(null as Usage?) { acc, u -> (acc ?: Usage()).merge(u) }
        } else {
            json(body)?.let(wire::usageFromBody)
        }

    /** The tier the provider says it served the request on (T385.12.2); the last stream mention wins. */
    fun serviceTier(
        contentType: String?,
        body: ByteArray,
    ): String? = if (isSse(contentType)) sseEvents(body).mapNotNull(::tier).lastOrNull() else json(body)?.let(::tier)

    private fun tier(v: JsonElement): String? {
        val o = v as? JsonObject
        return o.str("service_tier")
            ?: o?.get("usage").str("service_tier")
            ?: o?.get("response").str("service_tier")
            ?: (o?.get("message") as? JsonObject)?.get("usage").str("service_tier")
    }
}
