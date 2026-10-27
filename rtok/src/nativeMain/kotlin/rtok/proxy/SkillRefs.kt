// Port of `collect_skill_refs` in src/proxy/wire.rs: skill bodies on Anthropic (`tool_result` then `text`) and
// Chat (`role: tool` then a user string). The next user text after `Launching skill:` must start with the
// skill banner.
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.plugin.sdk.*

internal object SkillRefs {
    private const val SKILL_BODY = "Base directory for this skill:"
    private const val LAUNCHING = "Launching skill: "

    private fun resultText(content: JsonElement?): String? =
        when (content) {
            is JsonPrimitive -> content.takeIf { it.isString }?.content
            is JsonArray -> content.singleOrNull()?.let { it.str("text") ?: (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            else -> null
        }

    private fun launchingPair(
        id: String?,
        content: JsonElement?,
    ): Pair<String, String>? {
        val name =
            resultText(content)
                ?.trim()
                ?.takeIf { it.startsWith(LAUNCHING) }
                ?.removePrefix(LAUNCHING)
                ?.trim()
        return if (id == null || name.isNullOrEmpty()) null else id to name
    }

    fun collect(req: WireRequest): List<SkillRef> =
        buildList {
            var pending: Pair<String, String>? = null
            for (e in turnEntries(req, "messages")) {
                when (e.obj.str("role")) {
                    "tool" -> {
                        pending = launchingPair(e.obj.str("tool_call_id"), e.obj["content"])
                    }

                    "user" -> {
                        when (val c = e.obj["content"]) {
                            is JsonPrimitive -> {
                                val p = pending
                                pending = null
                                if (p != null && c.isString && c.content.startsWith(SKILL_BODY)) {
                                    add(SkillRef(req, listOf("messages", e.index, "content"), e.turn, p.first, p.second))
                                }
                            }

                            is JsonArray -> {
                                c.forEachIndexed { j, b ->
                                    if (b.str("type") == "tool_result") {
                                        pending = launchingPair(b.str("tool_use_id"), (b as JsonObject)["content"])
                                        return@forEachIndexed
                                    }
                                    val p = pending
                                    if (p != null && b.str("text")?.startsWith(SKILL_BODY) == true) {
                                        pending = null
                                        add(SkillRef(req, listOf("messages", e.index, "content", j, "text"), e.turn, p.first, p.second))
                                    }
                                }
                            }

                            else -> {
                                pending = null
                            }
                        }
                    }
                }
            }
        }
}
