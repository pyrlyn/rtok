// Port of the usage side of src/measure/jsonl.rs: Claude Code transcript lines, one `Usage` per turn (a repeated
// `message.id` is another streamed block of the same turn). The tool, image and injection fields `rtok stats`
// reads join this parser when stats is ported.
package rtok.measure

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import rtok.sys.forEachFileLine

data class Usage(
    val inputTokens: Long,
    val cacheCreationInputTokens: Long,
    val cacheReadInputTokens: Long,
    val outputTokens: Long,
    val turn: Int,
    /** Unix seconds of the line; 0 when it has none. */
    val ts: Long,
    val model: String?,
)

class Parsed {
    var lines = 0L
    var malformed = 0L
    var duplicates = 0L
    var turns = 0
    val usages = ArrayList<Usage>()
    private val seenIds = HashSet<String>()

    /** `count_line`: a blank line is skipped, an unparsable one counted as malformed. */
    fun countLine(raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return
        lines++
        parseJson(line)?.let(::ingest) ?: malformed++
    }

    private fun ingest(v: JsonElement) {
        val ty = v.field("type").str() ?: ""
        val msg = v.field("message") ?: v
        val repeat = msg.field("id").str()?.let { !seenIds.add(it) } ?: false
        val isTurn = (ty == "user" || ty == "assistant") && !repeat
        if (isTurn) {
            turns++
        } else if (repeat) {
            duplicates++
        }
        val turn = (turns - 1).coerceAtLeast(0)
        val u = (usageOf(msg) ?: usageOf(v))?.takeIf { isTurn } ?: return
        usages += u.copy(turn = turn, ts = lineTs(v), model = msg.field("model").str())
    }

    private fun usageOf(v: JsonElement): Usage? {
        val u = v.field("usage") as? JsonObject ?: return null
        return Usage(num(u["input_tokens"]), num(u["cache_creation_input_tokens"]), num(u["cache_read_input_tokens"]), num(u["output_tokens"]), 0, 0, null)
    }

    /** One provider counter, saturated at `u32::MAX` like the Rust column. */
    private fun num(v: JsonElement?): Long = (v as? JsonPrimitive)?.takeIf { !it.isString }?.let { p -> p.longOrNull?.takeIf { it >= 0 } ?: p.content.toULongOrNull()?.let { U32_MAX } }?.coerceAtMost(U32_MAX) ?: 0

    private companion object {
        const val U32_MAX = 4_294_967_295L
    }
}

/** `line_ts`: the line's RFC 3339 `timestamp` as unix seconds; 0 when it has none. */
internal fun lineTs(v: JsonElement): Long = rfc3339Seconds(v.field("timestamp").str())

fun parseJsonl(text: String): Parsed = Parsed().also { p -> text.lines().forEach(p::countLine) }

/** `parse_path`: null when the file cannot be opened; a line that is not UTF-8 counts as malformed. */
fun parsePath(path: String): Parsed? {
    val out = Parsed()
    val ok =
        forEachFileLine(path) { line ->
            if (line == null) {
                out.lines++
                out.malformed++
            } else {
                out.countLine(line)
            }
        }
    return out.takeIf { ok }
}
