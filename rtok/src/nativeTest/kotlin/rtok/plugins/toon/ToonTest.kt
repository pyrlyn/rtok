package rtok.plugins.toon

import kotlin.test.*
import kotlinx.serialization.json.*
import rtok.config.Config
import rtok.plugin.sdk.*
import rtok.proxy.AnthropicWire
import rtok.shortId
import rtok.store.*
import rtok.support.*

private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
private fun prettyText(v: JsonElement) = pretty.encodeToString(JsonElement.serializer(), v)
private fun j(s: String) = Json.parseToJsonElement(s)

/** Toon on/off, `min_rows`, and archive `keep_turns` (0: single-turn encoding tests). */
private fun toon(enabled: Boolean, minRows: Int, keep: UInt = 0u): (Config) -> Config = { c ->
    c.copy(plugins = c.plugins.copy(toon = c.plugins.toon.copy(enabled = enabled, minRows = minRows.toUInt()), archive = c.plugins.archive.copy(keepTurns = keep)))
}

private fun toolReq(table: JsonElement) = j("""{"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":${JsonPrimitive(prettyText(table))}}]}]}""")
private fun sixTurnReq(table: JsonElement) = buildJsonObject {
    putJsonArray("messages") {
        for (i in 1..6) {
            addJsonObject { put("role", "user"); putJsonArray("content") { addJsonObject { put("type", "tool_result"); put("tool_use_id", "t$i"); put("content", prettyText(table)) } } }
            if (i < 6) addJsonObject { put("role", "assistant"); put("content", "ok") }
        }
    }
}
private fun rows3x4() = j("""[{"a":11,"b":"alpha-value-one","c":33,"d":"delta-value-one"},{"a":44,"b":"beta-value-twoo","c":66,"d":"delta-value-two"},{"a":77,"b":"gamma-value-thr","c":99,"d":"delta-value-tre"}]""")
private fun filter(body: JsonElement, cx: Ctx): Pair<List<Measurement>, JsonElement> {
    val req = WireRequest(AnthropicWire, body); return ToonPlugin.proxyFilter(req, cx) to req.body
}
private fun JsonElement.firstContent() = jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content

// ---- the round-trip oracle for `encode` (test-only, as in Rust)
private fun decode(toon: String): JsonElement? {
    val nl = toon.indexOf('\n'); if (nl < 0) return null
    val s = toon.substring(0, nl).trim().removeSuffix(":").takeIf { it.length < toon.substring(0, nl).trim().length } ?: return null
    val brace = s.indexOf('{'); if (brace < 0 || !s.startsWith('[') || !s.substring(0, brace).endsWith(']') || !s.endsWith('}')) return null
    val keys = s.substring(brace + 1, s.length - 1).split(',')
    val declared = s.substring(1, s.indexOf(']')).toIntOrNull() ?: return null
    val rows = toon.substring(nl + 1).split('\n').map { line ->
        val cells = splitCells(line.trimStart()) ?: return null
        if (cells.size != keys.size) return null
        JsonObject(keys.zip(cells.map(::decodeCell)).toMap(LinkedHashMap()))
    }
    return if (rows.size == declared) JsonArray(rows) else null
}
private fun splitCells(line: String): List<String>? {
    val cells = ArrayList<String>(); var start = 0
    while (true) {
        var i = start
        if (start < line.length && line[start] == '"') {
            i++; var closed = false
            while (i < line.length) { val c = line[i++]; if (c == '\\') { if (i >= line.length) return null; i++ } else if (c == '"') { closed = true; break } }
            if (!closed || (i < line.length && line[i] != ',')) return null
        }
        val comma = line.indexOf(',', i)
        if (comma < 0) { cells += line.substring(start); return cells }
        cells += line.substring(start, comma); start = comma + 1
    }
}
private fun decodeCell(s: String): JsonElement {
    if (s.isEmpty()) return JsonNull
    if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) return JsonPrimitive(buildString {
        val t = s.substring(1, s.length - 1); var i = 0
        while (i < t.length) { val c = t[i++]; if (c != '\\') { append(c); continue }
            if (i >= t.length) { append('\\'); break }
            when (val n = t[i++]) { 'n' -> append('\n'); 'r' -> append('\r'); 't' -> append('\t'); '"' -> append('"'); '\\' -> append('\\'); else -> { append('\\'); append(n) } } }
    })
    return if (looksScalar(s) && s != "null") Json.parseToJsonElement(s) else JsonPrimitive(s)
}

class ToonTest {
    @Test fun defaultOffLeavesBytesIdentical() = withRuntime("toon-off", toon(false, 3)) { cx ->
        val body = toolReq(j("""[{"a":1,"b":2,"c":3},{"a":4,"b":5,"c":6},{"a":7,"b":8,"c":9},{"a":10,"b":11,"c":12}]"""))
        val (ms, out) = filter(body, Ctx(cx)); assertTrue(ms.isEmpty()); assertEquals(body, out)
    }

    @Test fun encodes3x4TableAndDecodeRecoversKeys() = withRuntime("toon-enc", toon(true, 3)) { cx ->
        val (ms, out) = filter(toolReq(rows3x4()), Ctx(cx))
        assertEquals(1, ms.size); assertTrue(ms[0].afterBytes < ms[0].beforeBytes)
        val decoded = decode(out.firstContent().substringAfter('\n'))!!
        assertEquals(listOf("a", "b", "c", "d"), decoded.jsonArray[0].jsonObject.keys.toList())
    }

    @Test fun roundTripValues() {
        for (cell in listOf("beta\nline-two", "gamma\rvalue", "delta\tvalue", "", "\"quoted\"", "123", "true", "null", "x,y", ",", "\",\"",
            "back\\slash", "end\\", "a\\\"b,c", "{k,v}", " lead, and trail ", "bell\u0007,tab\t,nl\n")) {
            val table = buildJsonArray {
                add(j("""{"a":11,"b":"alpha-value-one","c":33,"d":"delta-value-one"}"""))
                addJsonObject { put("a", 44); put("b", cell); put("c", 66); put("d", "delta-value-two") }
                add(j("""{"a":77,"b":"gamma-value-thr","c":99,"d":"delta-value-tre"}"""))
            }
            val encoded = encode(table, tabularKeys(table, 3)!!)
            assertEquals(table.size, encoded.substringAfter('\n').split('\n').size, "each row must be one physical line: $cell")
            assertEquals(table, decode(encoded), cell)
        }
    }

    /** T560: every awkward cell shape reads back verbatim with the declared counts. */
    @Test fun generatedCellsRoundTripWithDeclaredCounts() {
        val cells = listOf("", ",", "x,y", "\"", "\\", "\\\"", "a\\nb", "\n", "\r\n", "\t", "123", "-1.5e3", "true", "null", " x", "x ", "{}", "é,ü", "plain")
        val table = JsonArray(cells.flatMap { l -> cells.map { r -> buildJsonObject { put("a", l); put("b", r); put("c", 1); put("d", JsonNull) } } })
        assertEquals(table, decode(encode(table, tabularKeys(table, 1)!!)))
        val one = j("""[{"a":"x,y","b":1,"c":true}]""")
        assertEquals(one, decode(encode(one.jsonArray, tabularKeys(one, 1)!!)))
    }

    @Test fun malformedRowsAreRejected() {
        for (t in listOf("[1]{a,b}:\n  \"x,1", "[1]{a,b}:\n  \"x\"y,1", "[1]{a,b}:\n  \"x,y\"", "[1]{a,b}:\n  x,y,z", "[2]{a,b}:\n  x,y")) assertNull(decode(t), t)
    }

    @Test fun liveZoneTurnsAreUntouched() = withRuntime("toon-live", toon(true, 3, keep = 4u)) { cx ->
        val table = rows3x4(); val original = sixTurnReq(table)
        val (ms, out) = filter(original, Ctx(cx))
        assertEquals(2, ms.size, "only older-than-live-zone results encode")
        val texts = (0 until 6).map { out.jsonObject["messages"]!!.jsonArray[2 * it].jsonObject["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content }
        assertTrue(texts[0].startsWith("[toon ")); assertTrue(texts[1].startsWith("[toon "))
        texts.drop(2).forEach { assertEquals(prettyText(table), it, "live-zone turn must stay original") }
        assertEquals(out, filter(original, Ctx(cx)).second)
    }

    @Test fun anArchivePointerIsNotReplayedAsAToonSaving() = withRuntime("toon-archive-owned", toon(true, 3)) { cx ->
        val table = rows3x4(); val id = cx.putArchive(prettyText(table).encodeToByteArray())
        cx.store.putArchiveDecision("t", id, "s", "[archived x: 3 lines]")
        val body = toolReq(table); val (ms, out) = filter(body, Ctx(cx))
        assertTrue(ms.isEmpty()); assertEquals(body, out)
    }

    /** MCP tools return text blocks; the table inside encodes and the archive keeps the text. */
    @Test fun textBlockContentEncodes() = withRuntime("toon-blocks", toon(true, 3)) { cx ->
        val text = prettyText(rows3x4())
        val body = buildJsonObject { putJsonArray("messages") { addJsonObject { put("role", "user"); putJsonArray("content") { addJsonObject {
            put("type", "tool_result"); put("tool_use_id", "t"); putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } } } } } } }
        val (ms, _) = filter(body, Ctx(cx)); assertEquals(1, ms.size)
        assertEquals(text, cx.store.getArchive(ms[0].refId!!, cx.config.core.archiveDir)!!.decodeToString())
    }

    /** Two compact rows are larger as pointer + TOON than as JSON: left alone, no decision written. */
    @Test fun aTableThatWouldGrowIsLeftAlone() = withRuntime("toon-grow", toon(true, 2)) { cx ->
        val body = j("""{"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"[{\"a\":1,\"b\":2,\"c\":3},{\"a\":4,\"b\":5,\"c\":6}]"}]}]}""")
        val (ms, out) = filter(body, Ctx(cx)); assertTrue(ms.isEmpty()); assertEquals(body, out)
        assertNull(cx.archiveDecision("t"))
    }

    @Test fun keysWithDelimitersDoNotEncode() =
        assertNull(tabularKeys(j("""[{"a,b":1,"c":2,"d":3},{"a,b":4,"c":5,"d":6},{"a,b":7,"c":8,"d":9}]"""), 3))

    @Test fun archivedBytesMatchOriginalText() = withRuntime("toon-bytes", toon(true, 3)) { cx ->
        val (ms, _) = filter(toolReq(rows3x4()), Ctx(cx))
        assertEquals(prettyText(rows3x4()), cx.store.getArchive(ms[0].refId!!, cx.config.core.archiveDir)!!.decodeToString())
    }

    /** The pointer prints the 8-char prefix; replays are deterministic; an expanded id is not re-encoded. */
    @Test fun pointerIsShortDeterministicAndAnExpandedIdStaysOriginal() = withRuntime("toon-expand", toon(true, 3)) { cx ->
        val (ms, first) = filter(toolReq(rows3x4()), Ctx(cx))
        val id = ms[0].refId!!; val encoded = first.firstContent()
        assertEquals(64, id.length); assertTrue(encoded.startsWith("[toon ${shortId(id)}]"), encoded); assertFalse(id in encoded)
        assertEquals(encoded, filter(toolReq(rows3x4()), Ctx(cx)).second.firstContent(), "decision must be deterministic")
        cx.store.markExpanded(id)
        val (ms3, third) = filter(toolReq(rows3x4()), Ctx(cx))
        assertEquals(0, ms3.size); assertEquals(prettyText(rows3x4()), third.firstContent(), "expanded id must not re-encode")
    }
}
