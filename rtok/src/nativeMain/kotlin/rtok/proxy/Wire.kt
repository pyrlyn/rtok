// Port of the ToolResults helpers in src/proxy/wire.rs (the rest of the wire lands with Phase 7).
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.plugin.sdk.*

internal fun JsonElement?.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** One entry of a turn-ordered array: its index, the object, and its turn (user messages after it). */
internal class TurnEntry(
    val index: Int,
    val obj: JsonObject,
    val turn: Int,
)

/**
 * Rust `turn_setup` + the per-wire loop: walks `body[field]`, counting `user` entries from the end.
 * An entry's turn is taken after counting it when it is a user message (Rust `seen += 1` first).
 */
internal fun turnEntries(
    req: WireRequest,
    field: String,
): List<TurnEntry> {
    val entries = (req.body as? JsonObject)?.get(field) as? JsonArray ?: return emptyList()
    val total = entries.count { it.str("role") == "user" }
    var seen = 0
    return entries.mapIndexedNotNull { i, e ->
        val o = e as? JsonObject ?: return@mapIndexedNotNull null
        if (o.str("role") == "user") seen++
        TurnEntry(i, o, total - seen)
    }
}

/** A provider request/response shape supported by the proxy (Rust `Wire`). */
interface Wire : ToolResults {
    /** Whether this wire owns the request path. */
    fun matches(path: String): Boolean

    /** Provider slug for the request's dimension row. */
    val provider: String get() = Wires.OPENAI_PROVIDER

    /** `usage.api` discriminator (T11.6). */
    val api: String

    /** Provider session identity; defaults to the non-empty `user` field of both OpenAI wires. */
    fun sessionId(body: JsonElement): String? = body.str("user")?.takeIf { it.isNotEmpty() }

    /** Model slug; defaults to the body's `model` field. */
    fun model(
        path: String,
        body: JsonElement?,
    ): String? = body.str("model")

    fun usageFromBody(body: JsonElement): Usage?

    fun usageFromSse(event: JsonElement): Usage?

    /** Request shaping before forwarding; the new body, or null when nothing changed. */
    fun prepareRequest(
        body: JsonElement,
        includeUsage: Boolean,
    ): JsonElement? = null

    /** The billable total the `tokens` ledger records; the four counters are disjoint. */
    fun providerTotal(u: Usage): Long =
        u.input
            .satAdd(u.cacheCreate)
            .satAdd(u.cacheRead)
            .satAdd(u.output)
}

object Wires {
    const val ANTHROPIC_PROVIDER = "anthropic"
    const val OPENAI_PROVIDER = "openai"
    const val GEMINI_PROVIDER = "gemini"
    const val API_ANTHROPIC = "anthropic"
    const val API_OPENAI_CHAT = "openai_chat"
    const val API_OPENAI_RESPONSES = "openai_responses"
    const val API_GEMINI = "gemini"

    private val all: List<Wire> by lazy { listOf(AnthropicWire, OpenAiChatWire, OpenAiResponsesWire, GeminiWire) }

    /** The wire matching [path], if this build understands it. */
    fun forPath(path: String): Wire? = all.firstOrNull { it.matches(path) }
}
