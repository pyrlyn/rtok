@file:Suppress("LoopWithTooManyJumpStatements", "ReturnCount")

// Port of src/measure/skills_listing.rs (T71.4): the skill-listing overhead in a proxied `/v1/messages` request body.

package rtok.measure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** One measured `<available_skills>` block: its bytes (tags included), skills, and framing bytes per skill. */
data class SkillsListingMeasure(
    val blockBytes: Long,
    val count: Int,
    val framingBytesPerSkill: Long,
)

object SkillsListing {
    private const val START = "<available_skills>"
    private const val END = "</available_skills>"
    private const val OPEN = "<agent_skill"
    private const val CLOSE = "</agent_skill>"

    /** The largest skills block in the request's system prompt, or null. */
    fun measure(request: ByteArray): SkillsListingMeasure? {
        val root = runCatching { Json.parseToJsonElement(request.decodeToString(throwOnInvalidSequence = true)) }.getOrNull() ?: return null
        return systemTexts(root).mapNotNull(::measureBlock).fold(null as SkillsListingMeasure?) { best, m ->
            if (best == null || m.blockBytes > best.blockBytes) m else best
        }
    }

    private fun systemTexts(root: JsonElement): List<String> =
        when (val s = root.field("system")) {
            is JsonPrimitive -> listOfNotNull(s.str())
            is JsonArray -> s.mapNotNull { b -> b.field("text").str() ?: b.str() }
            else -> emptyList()
        }

    private fun measureBlock(text: String): SkillsListingMeasure? {
        val start = text.indexOf(START).takeIf { it >= 0 } ?: return null
        val end = text.indexOf(END, start).takeIf { it >= 0 } ?: return null
        val block = text.substring(start, end + END.length)
        val entries = entries(block)
        if (entries.isEmpty()) return null
        val framing = entries.sumOf { (e, d) -> (utf8Len(e) - utf8Len(d)).coerceAtLeast(0) } / entries.size
        return SkillsListingMeasure(utf8Len(block), entries.size, framing)
    }

    /** `(entry, description)` per listed skill. */
    private fun entries(block: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var rest = block
        while (true) {
            val i = rest.indexOf(OPEN).takeIf { it >= 0 } ?: break
            rest = rest.substring(i)
            val close = rest.indexOf(CLOSE).takeIf { it >= 0 } ?: break
            val entry = rest.substring(0, close + CLOSE.length)
            val ds = entry.indexOf('>') + 1
            val de = entry.lastIndexOf('<')
            if (ds in 1 until de) out += entry to entry.substring(ds, de)
            rest = rest.substring(close + CLOSE.length)
        }
        return out
    }
}
