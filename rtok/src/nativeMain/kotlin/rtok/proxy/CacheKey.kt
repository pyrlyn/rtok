// Port of the key half of src/proxy/semantic_cache.rs (P31): what a cached prompt is made of (provider, model,
// flattened text for the embedding, typed digests, tools fingerprint, every other parameter, the caller), and
// the canonical hashes over it. Key order and `cache_control` never split a key.
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.config.SemanticCache
import rtok.store.SerdeJson
import rtok.store.hexSha256
import rtok.sys.Sha256

/** `full` is a message's digest; `shape` the same with its text blanked (what the semantic tier keeps). */
data class TypedMessage(
    val full: String,
    val shape: String,
)

data class CachePrompt(
    val provider: String,
    val model: String,
    val system: String?,
    val messages: List<Pair<String, String>>,
    val typed: List<TypedMessage>,
    val systemTyped: String?,
    val toolsFingerprint: String?,
    val params: JsonElement,
    val caller: String = "",
) {
    /** The same prompt asked by [caller] (see [CacheKey.callerIdentity]). */
    fun withCaller(caller: String) = copy(caller = caller)

    /** The serde shape of the Rust struct (skipped `None`s and empty caller), the input of every hash. */
    internal fun toJson(): JsonObject =
        buildJsonObject {
            put("provider", provider)
            put("model", model)
            system?.let { put("system", it) }
            putJsonArray("messages") {
                messages.forEach { (r, t) ->
                    addJsonArray {
                        add(r)
                        add(t)
                    }
                }
            }
            putJsonArray("typed") {
                typed.forEach {
                    addJsonObject {
                        put("full", it.full)
                        put("shape", it.shape)
                    }
                }
            }
            systemTyped?.let { put("system_typed", it) }
            toolsFingerprint?.let { put("tools_fingerprint", it) }
            put("params", params)
            if (caller.isNotEmpty()) put("caller", caller)
        }
}

object CacheKey {
    /** Headers that identify the caller or change the response for the same body. */
    private val CALLER_HEADERS =
        listOf("x-api-key", "authorization", "x-goog-api-key", "anthropic-version", "anthropic-beta", "openai-organization", "openai-project")

    /** Fields excluded from `params`: hashed on their own, never cached (`stream`) or tracking ids. */
    private val EXCLUDED = setOf("messages", "model", "system", "tools", "stream", "metadata", "user")

    fun buildPrompt(
        wire: Wire,
        body: JsonElement,
        cfg: SemanticCache,
    ): CachePrompt? {
        val model = body.str("model") ?: return null
        val o = body as JsonObject
        return CachePrompt(
            provider = if (cfg.cacheByProvider) wire.provider else "",
            model = if (cfg.cacheByModel) model else "",
            system = o["system"]?.let(CacheText::contentText),
            messages = messagesText(o),
            typed = withRole(o).map { TypedMessage(CacheText.digest(it, false), CacheText.digest(it, true)) },
            systemTyped = o["system"]?.let { CacheText.digest(it, false) },
            toolsFingerprint = toolsFingerprint(o),
            params = CacheText.canonical(JsonObject(o.filterKeys { it !in EXCLUDED })),
        )
    }

    /** sha256 over the caller headers (T323); the raw secret never reaches the key. */
    fun callerIdentity(headers: List<Pair<String, String>>): String {
        var buf = ByteArray(0)
        for (name in CALLER_HEADERS) {
            buf += name.encodeToByteArray()
            for ((k, v) in headers) if (k.equals(name, ignoreCase = true)) buf += byteArrayOf(0) + v.encodeToByteArray()
            buf += byteArrayOf(1)
        }
        return Sha256.hex(buf)
    }

    /** [callerIdentity] plus method and resolved upstream URL (T533), hashed so a `?key=` never shows. */
    fun cacheScope(
        headers: List<Pair<String, String>>,
        method: String,
        target: String,
    ): String {
        var buf = callerIdentity(headers).encodeToByteArray()
        for (part in listOf(method, target)) buf += byteArrayOf(0) + part.encodeToByteArray()
        return Sha256.hex(buf)
    }

    fun eligible(
        body: JsonElement,
        cfg: SemanticCache,
    ): Boolean {
        val o = body as? JsonObject ?: return false
        val users = (o["messages"] as? JsonArray)?.count { it.str("role") == "user" } ?: 0
        val tools = o["tools"] as? JsonArray
        return o["stream"] != JsonPrimitive(true) &&
            users in 1..cfg.maxMessages.toInt() &&
            !(cfg.requireEmptyTools && !tools.isNullOrEmpty())
    }

    fun canonicalHash(p: CachePrompt): ByteArray = Sha256.digest(SerdeJson.encode(p.toJson()).encodeToByteArray())

    /** Everything but the last user message, hashed: the semantic tier compares only prompts sharing it. */
    internal fun scopeHash(p: CachePrompt): ByteArray {
        val i = p.messages.indexOfLast { it.first == "user" }
        if (i < 0) return canonicalHash(p)
        val msgs = p.messages.toMutableList().also { it[i] = it[i].first to "" }
        val typed = p.typed.toMutableList().also { if (i < it.size) it[i] = it[i].copy(full = "") }
        return canonicalHash(p.copy(messages = msgs, typed = typed))
    }

    private fun withRole(o: JsonObject) = (o["messages"] as? JsonArray).orEmpty().filter { it.str("role") != null }

    private fun messagesText(o: JsonObject): List<Pair<String, String>> =
        withRole(o).map { m ->
            val mo = m as JsonObject
            val text = StringBuilder(mo["content"]?.let(CacheText::contentText).orEmpty())
            // OpenAI Chat: `tool_calls` and `tool_call_id` sit beside `content` (T303).
            mo["tool_calls"]?.let { text.append(" tool_calls ").append(CacheText.encode(it)) }
            mo.str("tool_call_id")?.let { text.append(" tool_call_id ").append(it) }
            mo.str("role")!! to text.toString()
        }

    /** Each tool's full definition hashed (T212), canonicalized per tool. */
    private fun toolsFingerprint(o: JsonObject): String? {
        val tools = (o["tools"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return null
        var buf = ByteArray(0)
        for (t in tools) buf += CacheText.encode(t).encodeToByteArray() + byteArrayOf(0)
        return hex8(Sha256.digest(buf))
    }

    @OptIn(ExperimentalStdlibApi::class)
    internal fun hex8(hash: ByteArray) = hash.copyOf(HEX8_BYTES).toHexString()

    private const val HEX8_BYTES = 4
}
