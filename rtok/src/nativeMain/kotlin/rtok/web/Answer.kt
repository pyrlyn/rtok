// Port of src/web/mod.rs `inbound`/`answer`, `allowlisted_plugin_enabled`, `message_frame` and protocol.rs
// `tag_reply` and `junk_reply` (T623, T330.7): the reply to one `/ws` client message; the graph page's requests are in
// GraphAnswer.kt. `doctor` is mod.rs `doctor_reply` (T331.12) over DoctorWeb's plan and apply.
package rtok.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import rtok.Expand
import rtok.agents.Junk
import rtok.agents.JunkReport
import rtok.agents.JunkWeb
import rtok.config.Config
import rtok.config.Validate
import rtok.doctor.DoctorWeb
import rtok.doctor.FixPlan
import rtok.doctor.Fixed
import rtok.doctor.Selection
import rtok.model.JunkPage
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
    /** The junk scan `plan` and `apply` run over; tests hand in a fixture report instead of the real home's. */
    private val junkReport: (Config) -> JunkReport = Junk::report,
    /** The `doctor` plan; tests hand in one that never reads the real host configs. */
    private val doctorPlan: (Config, Selection) -> FixPlan = DoctorWeb::planHere,
    /** The `doctor` apply, given the confirmed token. */
    private val doctorApply: (Config, Selection, String) -> Fixed = DoctorWeb::applyHere,
) : Inbound {
    /** The reply to [text], `req` written in when the request carried one; null for none. */
    override fun answer(text: String): String? {
        val v = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val req = (v["req"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        return reply(v)?.let { tagReply(it, req) }
    }

    private fun reply(v: JsonObject): String? =
        when {
            "expand" in v -> {
                (v["expand"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::expand)
            }

            "set" in v -> {
                set(v.getValue("set"))
            }

            "junk" in v -> {
                junk(v.getValue("junk"))
            }

            "doctor" in v -> {
                doctor(v.getValue("doctor"))
            }

            GraphAnswer.KEYS.any { it in v } -> {
                GraphAnswer(cfg).reply(v)
            }

            // Handled by the socket loop, which owns the subscription; a stray one here is a no-op.
            subscribe(v) != null -> {
                null
            }

            else -> {
                MALFORMED.firstOrNull { it.first in v }?.let { message(it.second) }
            }
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

    /**
     * mod.rs `junk_reply`: the upgrade's token and origin checks cover it; only `apply` removes, through the same plan,
     * re-checks and refusals as `agents junk clear`.
     */
    private fun junk(body: JsonElement): String {
        val o = body as? JsonObject
        val action = (o?.get("action") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val raw = o?.get("paths")
        val paths =
            when (raw) {
                null -> emptyList()
                is JsonArray -> raw.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                else -> listOf(null)
            }
        if (action !in JUNK_ACTIONS || null in paths) return message("junk needs an action")
        val c = cfg.value
        val report = junkReport(c)
        return if (action == "plan") {
            frame("junkplan", "plan" to JunkWeb.plan(c, report).json())
        } else {
            val cleared = JunkWeb.apply(c, report, paths.filterNotNull())
            JunkPage.forget()
            frame("junkcleared", "cleared" to cleared.json())
        }
    }

    /**
     * mod.rs `doctor_reply`: the upgrade's token and origin checks cover it like `set`; only `apply` writes, through
     * the engine's backup and refusals, and only the preview whose token it is given.
     */
    private fun doctor(body: JsonElement): String {
        val r = runCatching { STRICT.decodeFromJsonElement(DoctorRequest.serializer(), body) }.getOrNull()
        if (r == null || r.action !in JUNK_ACTIONS) return message("doctor needs an action and a selection")
        val c = cfg.value
        return if (r.action == "plan") {
            frame("doctorplan", "plan" to STRICT.encodeToJsonElement(FixPlan.serializer(), doctorPlan(c, r.selection)))
        } else {
            frame("doctorfixed", "fixed" to STRICT.encodeToJsonElement(Fixed.serializer(), doctorApply(c, r.selection, r.confirmed.orEmpty())))
        }
    }

    /** protocol.rs `DoctorRequest`. */
    @Serializable
    private class DoctorRequest(
        val action: String,
        val selection: Selection = Selection(),
        val confirmed: String? = null,
    )

    companion object {
        private val STRICT = Json { ignoreUnknownKeys = true }
        private val JUNK_ACTIONS = setOf("plan", "apply")

        /** A request whose shape did not parse, by its key, in Rust's order. */
        private val MALFORMED =
            listOf(
                "calls" to "calls needs a subscribe flag",
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
