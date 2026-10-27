// Port of the array side of src/agents/jsonc.rs (`Seg`, `locate`, `remove_at`, `is_empty_at`, `push_item`,
// `pull_items` and the span scanners they use): add or drop one element of a list in a JSONC file and leave every
// other byte (comments, trailing commas, indentation) as it was.
package rtok.mcp.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.HostJson

/** One step of a path into a JSONC document. */
sealed interface Seg {
    data class Key(
        val key: String,
    ) : Seg

    data class Index(
        val n: Int,
    ) : Seg
}

object JsoncItems {
    private fun parseAt(
        raw: String,
        path: String,
    ): JsonElement = runCatching { Jsonc.parse(raw) }.getOrElse { throw McpConfigException("$path: ${it.message}", it) }

    private fun renderEntry(
        entry: JsonElement,
        pad: Int,
    ): String =
        HostJson.pretty
            .encodeToString(JsonElement.serializer(), entry)
            .lines()
            .mapIndexed { n, l -> if (n == 0) l else " ".repeat(pad) + l }
            .joinToString("\n")

    private fun compact(v: JsonElement) = Json.encodeToString(JsonElement.serializer(), v)

    private fun qkey(s: String) = compact(JsonPrimitive(s))

    /** Whether [inner] holds no member, element or comment. */
    private fun isBlank(inner: String): Boolean {
        val stripped = Jsonc.stripComments(inner)
        return stripped.isBlank() && stripped.length == inner.length
    }

    /** Drop the member or element at [segs] with one adjacent comma; `false` when the path leads nowhere. */
    fun removeAt(
        raw: String,
        path: String,
        segs: List<Seg>,
    ): Pair<String, Boolean> {
        parseAt(raw, path)
        val at = JsoncScan.locate(raw, segs) ?: return raw to false
        return JsoncScan.exciseMember(raw, at.member, at.end) to true
    }

    /** Whether the object or array at [segs] holds nothing, not even a comment. */
    fun isEmptyAt(
        raw: String,
        segs: List<Seg>,
    ): Boolean = JsoncScan.locate(raw, segs)?.let { isBlank(raw.substring(it.start + 1, maxOf(it.start + 1, it.end - 1))) } ?: false

    private fun pushMember(
        text: StringBuilder,
        open: Int,
        key: String,
        value: JsonElement,
        pad: Int,
    ) {
        val s = text.toString()
        val close = JsoncScan.skipValue(s, open) ?: s.length
        val brace = close - 1
        val member = "${qkey(key)}: ${renderEntry(value, pad)}"
        val outer = " ".repeat(maxOf(0, pad - 2))
        val ind = " ".repeat(pad)
        val last = JsoncScan.lastValueEnd(s, open)
        when {
            isBlank(s.substring(open + 1, brace)) -> text.setRange(open + 1, brace, "\n$ind$member\n$outer")
            last != null -> text.insert(last, ",\n$ind$member")
            else -> text.insert(brace, "\n$ind$member\n$outer")
        }
    }

    private fun pushElement(
        text: StringBuilder,
        open: Int,
        end: Int,
        item: JsonElement,
    ) {
        val s = text.toString()
        val line = s.lastIndexOf('\n', open - 1) + 1
        val base = s.substring(line).takeWhile { it.isWhitespace() }
        var last: Pair<Int, Int>? = null
        var n = 0
        while (true) {
            val e = JsoncScan.nthElement(s, open, n) ?: break
            last = e
            n++
        }
        if (last == null) {
            val pad = base.length + 2
            val body = "\n${" ".repeat(pad)}${renderEntry(item, pad)}\n$base"
            if (isBlank(s.substring(open + 1, end - 1))) text.setRange(open + 1, end - 1, body) else text.insert(end - 1, body)
            return
        }
        val (start, stop) = last
        val before = s.substring(s.lastIndexOf('\n', start - 1) + 1, start)
        val add = if (before.isBlank()) ",\n$before${renderEntry(item, before.length)}" else ", ${compact(item)}"
        text.insert(stop, add)
    }

    /**
     * Add [item] to the array at `raw[keys[0]]…[keys[n]]`, creating the missing objects and array. A value of another
     * shape on the way is an error, never replaced.
     */
    fun pushItem(
        raw: String,
        path: String,
        keys: List<String>,
        item: JsonElement,
    ): String {
        require(keys.isNotEmpty()) { "pushItem needs a key" }
        val text =
            StringBuilder(
                if (Jsonc.stripComments(raw).isBlank()) {
                    "{}"
                } else {
                    parseAt(raw, path)
                    raw
                },
            )
        var open = JsoncScan.rootOpen(text.toString()) ?: throw McpConfigException("$path: not a JSON object")
        for ((depth, key) in keys.withIndex()) {
            val at = JsoncScan.findKey(text.toString(), open, key)
            if (at == null) {
                val value =
                    keys.drop(depth + 1).foldRight(JsonArray(listOf(item)) as JsonElement) { k, v -> JsonObject(mapOf(k to v)) }
                pushMember(text, open, key, value, 2 * (depth + 1))
                return text.toString()
            }
            val wantArray = depth + 1 == keys.size
            val want = if (wantArray) '[' else '{'
            if (text[at.start] != want) throw McpConfigException("$path: `$key` is not an ${if (wantArray) "array" else "object"}")
            if (wantArray) {
                pushElement(text, at.start, at.end, item)
                return text.toString()
            }
            open = at.start
        }
        error("the last key returns")
    }

    /**
     * Drop the elements [ours] accepts from the array at [keys], then the array and every object that leaves empty.
     * The new text and the count of dropped elements.
     */
    fun pullItems(
        raw: String,
        path: String,
        keys: List<String>,
        ours: (JsonElement) -> Boolean,
    ): Pair<String, Int> {
        val doc = parseAt(raw, path)
        val found = keys.fold(doc as JsonElement?) { v, k -> (v as? JsonObject)?.get(k) }
        val hits = (found as? JsonArray)?.let { a -> a.indices.filter { ours(a[it]) }.reversed() }.orEmpty()
        var text = raw
        val segs: MutableList<Seg> = keys.map { Seg.Key(it) }.toMutableList()
        for (i in hits) {
            segs += Seg.Index(i)
            text = removeAt(text, path, segs).first
            segs.removeAt(segs.lastIndex)
        }
        while (hits.isNotEmpty() && segs.isNotEmpty() && isEmptyAt(text, segs)) {
            text = removeAt(text, path, segs).first
            segs.removeAt(segs.lastIndex)
        }
        if (hits.isNotEmpty()) parseAt(text, path)
        return text to hits.size
    }
}
