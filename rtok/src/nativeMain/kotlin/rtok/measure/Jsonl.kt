// Port of src/measure/jsonl.rs (usage, tool and injection side): Claude Code transcript lines, one `Usage` per turn (a
// repeated `message.id` is another streamed block of the same turn), the tool_use / tool_result blocks, the skill
// bodies hooks inject (`isMeta` + `sourceToolUseID`), the compaction boundaries, and what `rtok stats` reads besides:
// assistant texts, thinking blocks, image blocks, the first user text and the `SubagentStart` hook context.
package rtok.measure

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

data class ToolUse(
    val id: String,
    val name: String,
    val input: JsonElement,
    val turn: Int,
)

data class ToolResult(
    val toolUseId: String,
    val content: String,
    val turn: Int,
)

/** A body a hook injected for a tool call (a `Skill` load): its byte length. */
data class Injected(
    val toolUseId: String,
    val bytes: Long,
    val turn: Int,
)

/** T125: one thinking (or redacted thinking) block: its text/data byte length. */
data class ThinkingBlock(
    val bytes: Long,
    val turn: Int,
)

/** T137: an `image` block inside a tool_result ([toolUseId] set) or a user message (empty). */
data class ImageBlock(
    val toolUseId: String,
    val bytes: Long,
    val tokens: Long,
    val sized: Boolean,
    val turn: Int,
)

class Parsed {
    val toolUses = ArrayList<ToolUse>()
    val toolResults = ArrayList<ToolResult>()
    val injected = ArrayList<Injected>()
    val assistantTexts = ArrayList<String>()
    val thinking = ArrayList<ThinkingBlock>()
    val images = ArrayList<ImageBlock>()

    /** T131: the first `user` turn's flattened content, whole. */
    var firstUserText: String? = null

    /** T131: every `SubagentStart` hook's `additionalContext`, joined by newlines. */
    var subagentStartContext = ""

    /** The turn count at every `compact_boundary`. */
    val compactions = ArrayList<Int>()

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

    @Suppress("CyclomaticComplexMethod")
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
        if (v.field("subtype").str() == "compact_boundary") compactions += turns
        if (ty == "user" && turn == 0 && firstUserText == null) firstUserText = flatten(msg.field("content"))
        val att = v.field("attachment")
        if (ty == "attachment" && att.field("type").str() == "hook_additional_context" && att.field("hookEvent").str() == "SubagentStart") {
            if (subagentStartContext.isNotEmpty()) subagentStartContext += "\n"
            subagentStartContext += flatten(att.field("content"))
        }
        val source = v.field("sourceToolUseID").str()
        if (ty == "user" && v.field("isMeta").bool() == true && !source.isNullOrEmpty()) {
            injected += Injected(source, utf8Len(flatten(msg.field("content"))), turn)
        }
        (usageOf(msg) ?: usageOf(v))?.takeIf { isTurn }?.let { u ->
            usages += u.copy(turn = turn, ts = lineTs(v), model = msg.field("model").str())
        }
        when (val content = msg.field("content")) {
            is JsonArray -> {
                content.forEach { block(it, ty, turn, repeat) }
            }

            is JsonPrimitive -> {
                if (ty == "assistant" && content.isString && content.content.isNotEmpty()) assistantTexts += content.content
            }

            else -> {}
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun block(
        b: JsonElement,
        ty: String,
        turn: Int,
        repeat: Boolean,
    ) {
        when (b.field("type").str()) {
            "tool_use" -> {
                toolUses += ToolUse(b.field("id").str().orEmpty(), b.field("name").str().orEmpty(), b.field("input") ?: JsonNull, turn)
            }

            "tool_result" -> {
                val id = b.field("tool_use_id").str().orEmpty()
                if (!repeat) (b.field("content") as? JsonArray)?.forEach { image(it, id, turn) }
                toolResults += ToolResult(id, flatten(b.field("content")), turn)
            }

            "text" -> {
                if (ty == "assistant") {
                    b
                        .field("text")
                        .str()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { assistantTexts += it }
                }
            }

            "image" -> {
                if (ty == "user" && !repeat) image(b, "", turn)
            }

            "thinking", "redacted_thinking" -> {
                if (ty == "assistant" && !repeat) {
                    val data = (b.field("thinking") ?: b.field("data")).str().orEmpty()
                    thinking += ThinkingBlock(utf8Len(data), turn)
                }
            }
        }
    }

    /** `push_image`: records [x] when it is an `image` block; base64 sources get bytes and pixel size. */
    private fun image(
        x: JsonElement,
        toolUseId: String,
        turn: Int,
    ) {
        if (x.field("type").str() != "image") return
        val data = x.pointer("/source/data").str().orEmpty()
        val dims = ImageCost.dims(data)
        images += ImageBlock(toolUseId, ImageCost.decodedLen(data), dims?.let { (w, h) -> ImageCost.tokens(w, h) } ?: 0, dims != null, turn)
    }

    /** `flatten_content`: a string, the strings and `text` fields of an array joined by newlines, else the JSON. */
    private fun flatten(v: JsonElement?): String =
        when (v) {
            null, JsonNull -> ""
            is JsonPrimitive -> if (v.isString) v.content else v.toString()
            is JsonArray -> v.mapNotNull { x -> (x as? JsonPrimitive)?.takeIf { it.isString }?.content ?: x.field("text").str() }.joinToString("\n")
            else -> v.toString()
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
