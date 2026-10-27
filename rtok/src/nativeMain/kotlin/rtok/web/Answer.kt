// Port of src/web/mod.rs `inbound`/`answer`, `allowlisted_plugin_enabled`, `message_frame` and protocol.rs
// `tag_reply` (T623): the reply to one `/ws` client message. Slice 1 of docs/web.md step 5: `expand` and `set`;
// the other requests answer with the notice Rust sends for a malformed one until their slices land.
package rtok.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import rtok.Expand
import rtok.config.Config
import rtok.config.Validate
import rtok.plugin.Runtime
import rtok.plugins.Registry
import kotlin.concurrent.AtomicReference

/** The config a `rtok web` process serves; a confirmed `set` swaps in the reloaded one. */
class LiveConfig(
    initial: Config,
) {
    private val ref = AtomicReference(initial)

    val value: Config get() = ref.value

    fun replace(cfg: Config) {
        ref.value = cfg
    }
}

class Answer(
    private val cfg: LiveConfig,
) : Inbound {
    /** The reply to [text], `req` written in when the request carried one; null for none. */
    override fun answer(text: String): String? {
        val v = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val req = (v["req"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        return reply(v)?.let { tagReply(it, req) }
    }

    private fun reply(v: JsonObject): String? =
        when {
            "expand" in v -> (v["expand"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::expand)

            "set" in v -> set(v.getValue("set"))

            // Handled by the socket loop, which owns the subscription; a stray one here is a no-op.
            subscribe(v) != null -> null

            else -> MALFORMED.firstOrNull { it.first in v }?.let { message(it.second) }
        }

    private fun subscribe(v: JsonObject): Boolean? = ((v["calls"] as? JsonObject)?.get("subscribe") as? JsonPrimitive)?.booleanOrNull

    private fun expand(id: String): String {
        val c = cfg.value
        val text =
            runCatching {
                Runtime.open(c, "expand").use { cx ->
                    Expand.fetch(cx, id)?.let { Expand.renderLines(it.decodeToString(), id, null, null, 0, c.expand.maxLines) }
                }
            }.getOrNull()
        return text?.let { frame("expand", "id" to JsonPrimitive(id), "text" to JsonPrimitive(it)) } ?: message("unknown archive id: $id")
    }

    private fun set(body: JsonElement): String? {
        val o = body as? JsonObject
        val key = (o?.get("key") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: if (o != null && "key" !in o) "" else null
        val value = (o?.get("value") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val c = cfg.value
        return when {
            key == null || value == null -> message("set needs a string key and a bool value")
            !allowlisted(c, key) -> message("refused key $key")
            else -> write(c, key, value)
        }
    }

    private fun write(
        c: Config,
        key: String,
        value: Boolean,
    ): String? =
        runCatching { Validate.set(c.home, key, value.toString(), false) }.fold(
            {
                runCatching { Config.loadFrom(c.home) }.getOrNull()?.let(cfg::replace)
                null
            },
            { message("config set $key: ${it.message ?: it}") },
        )

    companion object {
        /** A request whose shape did not parse, by its key, in Rust's order. */
        private val MALFORMED =
            listOf(
                "export" to "export needs a project, a level and a format",
                "import" to "import needs a name and the file's text",
                "project" to "unknown project request",
                "graph" to "graph needs a project",
                "diff" to "diff needs a project",
                "calls" to "calls needs a subscribe flag",
                "junk" to "junk needs an action",
                "doctor" to "doctor needs an action and a selection",
            )

        /** Only `plugins.<compiled-in id>.enabled` may be flipped from the page. */
        fun allowlisted(
            cfg: Config,
            key: String,
        ): Boolean {
            val id = if (key.startsWith("plugins.") && key.endsWith(".enabled")) key.removePrefix("plugins.").removeSuffix(".enabled") else ""
            return id.isNotEmpty() && '.' !in id && Registry.new(cfg).manifests().any { it.first.id == id }
        }

        fun message(text: String) = frame("message", "text" to JsonPrimitive(text))

        private fun frame(
            type: String,
            vararg fields: Pair<String, JsonElement>,
        ) = JsonObject(mapOf("type" to JsonPrimitive(type)) + fields).toString()

        /** `frame` with `req` spliced in first, as `Reply` serializes it; anything else goes out unchanged. */
        fun tagReply(
            frame: String,
            req: Long?,
        ): String = if (req != null && frame.startsWith("{\"")) "{\"req\":$req,${frame.substring(1)}" else frame
    }
}
