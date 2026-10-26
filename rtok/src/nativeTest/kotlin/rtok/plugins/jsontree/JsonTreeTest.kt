package rtok.plugins.jsontree

import kotlinx.serialization.json.*
import rtok.config.Config
import rtok.plugin.sdk.*
import rtok.plugins.JSON_TREE_PREFIX
import rtok.plugins.TOON_PREFIX
import rtok.plugins.toon.ToonPlugin
import rtok.plugins.toon.tabularKeys
import rtok.proxy.AnthropicWire
import rtok.shortId
import rtok.store.*
import rtok.support.*
import rtok.sys.Sha1
import kotlin.random.Random
import kotlin.test.*

private val pretty =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

private fun prettyText(v: JsonElement) = pretty.encodeToString(JsonElement.serializer(), v)

private fun j(s: String) = Json.parseToJsonElement(s)

private fun JsonObject.with(
    k: String,
    v: JsonElement,
) = JsonObject(LinkedHashMap(this).also { it[k] = v })

private fun bytes(v: JsonElement) = v.toString().encodeToByteArray().size

/** Pads `note` until the tree passes MIN_BYTES. */
private fun padded(root: JsonObject): JsonObject {
    var r = root
    var note = ""
    while (bytes(r) < MIN_BYTES) {
        note += "screen-note-"
        r = r.with("note", JsonPrimitive(note))
    }
    return r
}

private fun design(): JsonObject {
    val fill = j("""{"type":"SOLID","color":"#C0FFEE"}""")
    val children =
        (1..3).map { i ->
            var c =
                buildJsonObject {
                    put("id", "child-$i")
                    put("name", "Card $i")
                    put("type", "FRAME")
                    put("fills", fill)
                }
            if (i == 3) c = c.with("padding", j("""{"top":4,"right":8,"bottom":4,"left":8}"""))
            c
        }
    return padded(
        buildJsonObject {
            put("id", "root")
            put("name", "Screen")
            put("type", "FRAME")
            put("children", JsonArray(children))
        },
    )
}

private fun reverseKeys(v: JsonElement): JsonElement =
    when (v) {
        is JsonArray -> JsonArray(v.map(::reverseKeys))
        is JsonObject -> JsonObject(v.keys.reversed().associateWithTo(LinkedHashMap()) { reverseKeys(v.getValue(it)) })
        else -> v
    }

private fun sharedObject() = j("""{"top":4,"right":8,"bottom":4,"left":8,"unit":"px","mode":"fixed"}""")

private fun varId(shared: JsonElement) = contentId("V-", canonical(shared), HashMap())

private fun collision(literal: String) =
    padded(
        buildJsonObject {
            put("type", "FRAME")
            putJsonArray("children") {
                addJsonObject {
                    put("type", "ITEM")
                    put("value", sharedObject())
                }
                addJsonObject {
                    put("type", "ITEM")
                    put("value", sharedObject())
                }
                addJsonObject {
                    put("type", "ITEM")
                    put("value", literal)
                }
            }
        },
    )

private val on: (Config) -> Config = { c -> c.copy(plugins = c.plugins.copy(jsonTree = c.plugins.jsonTree.copy(enabled = true), archive = c.plugins.archive.copy(keepTurns = 0u))) }

private fun toolReq(v: JsonElement) =
    buildJsonObject {
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", "t")
                        put("content", prettyText(v))
                    }
                }
            }
        }
    }

private fun toolMessages(text: String) =
    buildJsonObject {
        putJsonArray("messages") {
            for (i in 1..6) {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", "t$i")
                            put("content", text)
                        }
                    }
                }
                if (i < 6) {
                    addJsonObject {
                        put("role", "assistant")
                        put("content", "ok")
                    }
                }
            }
        }
    }

private fun filter(
    body: JsonElement,
    cx: Ctx,
    p: Plugin = JsonTreePlugin,
): Pair<List<Measurement>, JsonElement> {
    val req = WireRequest(AnthropicWire, body)
    return p.proxyFilter(req, cx) to req.body
}

private fun JsonElement.content(i: Int = 0) =
    jsonObject["messages"]!!
        .jsonArray[i]
        .jsonObject["content"]!!
        .jsonArray[0]
        .jsonObject["content"]!!
        .jsonPrimitive.content

private fun table8() =
    JsonArray(
        (0 until 8).map { i ->
            buildJsonObject {
                put("alpha", "alpha-value-$i-padding")
                put("beta", "beta-value-$i-padding")
                put("gamma", i)
                put("delta", "delta-value-$i-padding")
            }
        },
    )

/** Random JSON like the Rust proptest strategy: depth ≤ 2, ≤ 3 children, small leaves. */
private fun randomJson(
    r: Random,
    depth: Int = 2,
): JsonElement {
    fun word(
        min: Int,
        max: Int,
    ) = (0 until r.nextInt(min, max + 1)).map { 'a' + r.nextInt(26) }.joinToString("")
    if (depth == 0 || r.nextInt(3) == 0) {
        return when (r.nextInt(4)) {
            0 -> JsonNull
            1 -> JsonPrimitive(r.nextBoolean())
            2 -> JsonPrimitive(r.nextInt(20))
            else -> JsonPrimitive(word(0, 6))
        }
    }
    return if (r.nextBoolean()) {
        JsonArray(List(r.nextInt(3)) { randomJson(r, depth - 1) })
    } else {
        JsonObject((0 until r.nextInt(3)).associateTo(LinkedHashMap()) { word(1, 4) to randomJson(r, depth - 1) }.toSortedMap())
    }
}

private fun <V> Map<String, V>.toSortedMap(): Map<String, V> = keys.sorted().associateWithTo(LinkedHashMap()) { getValue(it) }

class JsonTreeTest {
    @Test fun sha1MatchesKnownVectors() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Sha1.hex(ByteArray(0)))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Sha1.hex("abc".encodeToByteArray()))
    }

    @Test fun aTruncatedHashClashLengthensByFour() {
        val hash = Sha1.hex("beta".encodeToByteArray())
        val used = hashMapOf("EL-${hash.take(8)}" to "alpha")
        assertEquals("EL-${hash.take(12)}", contentId("EL-", "beta", used))
    }

    @Test fun repeatedChildrenShareOneVarAndOneElement() {
        val folded = foldJson(design())!!
        assertEquals(folded.text, foldJson(reverseKeys(design()))!!.text, "key order must not change the text")
        assertEquals(1, folded.templates)
        val varLines = folded.text.lines().filter { it.startsWith("V-") }
        assertEquals(1, varLines.size, folded.text)
        assertEquals(1, Regex("#C0FFEE").findAll(folded.text).count(), folded.text)
        val elLines = folded.text.lines().filter { it.startsWith("EL-") }
        assertEquals(1, elLines.size, folded.text)
        val el = elLines[0].substringBefore(':')
        assertEquals(2, folded.text.split("template=$el").size - 1, folded.text)
        assertTrue("padding=" in folded.text, folded.text)
        assertTrue("padding" !in varLines[0] && "\"top\"" !in varLines[0], varLines[0])
        assertTrue(folded.nodes >= 4)
    }

    @Test fun smallFlatAndTabularValuesAreLeftAlone() {
        assertNull(foldJson(j("""{"a":{"b":1}}""")))
        val flat = j("""{"a":"x","b":"y","c":"z"}""").jsonObject.with("note", JsonPrimitive("n".repeat(300)))
        assertTrue(bytes(flat) >= MIN_BYTES)
        assertNull(foldJson(flat), "no nested object")
        val table = table8()
        assertTrue(bytes(table) >= MIN_BYTES)
        assertNotNull(tabularKeys(table, 1))
        assertNull(foldJson(table), "toon keeps the table")
    }

    @Test fun aLiteralEqualToAVarIdStaysDistinctFromTheReference() {
        val id = varId(sharedObject())
        val folded = foldJson(collision(id))!!
        assertEquals(1, foldJson(collision("ordinary-literal"))!!.templates)
        assertEquals(1, folded.templates, folded.text)
        assertEquals(2, folded.text.split("template=").size - 1, folded.text)
        val literal = folded.text.lines().first { "template=" !in it && "[ITEM]" in it }
        assertTrue("value=\"$id\"" in literal, literal)
        val element = folded.text.lines().first { it.startsWith("EL-") }
        assertTrue("\"value\":$id}" in element, element)
        assertEquals(2, folded.text.split("VARS:").size, folded.text)
        assertEquals(folded.text, foldJson(collision(id))!!.text)
        assertEquals(folded.text, foldJson(reverseKeys(collision(id)))!!.text)
    }

    @Test fun aReferenceShapedLiteralDoesNotMarkAVarUsed() {
        val inner = j("""{"a":"alpha","b":"beta","c":"gamma-gamma"}""")
        val outer =
            buildJsonObject {
                put("p", inner)
                put("q", inner)
                put("r", "keep-this-outer")
            }
        val id = varId(inner)
        val root =
            padded(
                buildJsonObject {
                    put("type", "FRAME")
                    putJsonArray("children") {
                        addJsonObject {
                            put("type", "ITEM")
                            put("value", outer)
                        }
                        addJsonObject {
                            put("type", "ITEM")
                            put("value", id)
                        }
                    }
                },
            )
        val folded = foldJson(root)!!
        assertFalse("VARS:" in folded.text, folded.text)
        assertTrue("value=\"$id\"" in folded.text, folded.text)
    }

    @Test fun theProxyFoldsTheCollisionAndTheArchiveKeepsTheOriginal() =
        withRuntime("jt-collision", on) { cx ->
            val id = varId(sharedObject())
            var value = collision(id)
            val kids =
                value["children"]!!.jsonArray +
                    (0 until 8).map { i ->
                        buildJsonObject {
                            put("type", "ITEM")
                            put("value", sharedObject())
                            put("n", i)
                        }
                    }
            value = value.with("children", JsonArray(kids))
            val (ms, out) = filter(toolReq(value), Ctx(cx))
            assertEquals(1, ms.size)
            assertTrue(ms[0].estAfter < ms[0].estBefore)
            val text = out.content()
            assertTrue("value=\"$id\"" in text, text)
            assertEquals(text, filter(toolReq(value), Ctx(cx)).second.content())
            assertEquals(prettyText(value), cx.store.getArchive(ms[0].refId!!, cx.config.core.archiveDir)!!.decodeToString())
        }

    /** Rust proptest (32 cases): folding is deterministic and independent of key order. */
    @Test fun foldIsDeterministic() {
        val r = Random(20260)
        repeat(32) {
            val v = randomJson(r)
            val once = foldJson(v)?.text
            assertEquals(once, foldJson(v)?.text)
            assertEquals(once, foldJson(reverseKeys(v))?.text)
        }
    }

    @Test fun defaultOffLeavesBytesIdentical() =
        withRuntime("jt-off", { c -> on(c).let { it.copy(plugins = it.plugins.copy(jsonTree = it.plugins.jsonTree.copy(enabled = false))) } }) { cx ->
            val body = toolReq(design())
            val (ms, out) = filter(body, Ctx(cx))
            assertTrue(ms.isEmpty())
            assertEquals(body, out)
        }

    @Test fun anOldResultIsFoldedAndTheArchiveReturnsTheOriginal() =
        withRuntime("jt-fold", on) { cx ->
            val (ms, out) = filter(toolReq(design()), Ctx(cx))
            assertEquals(1, ms.size)
            assertEquals("json_tree", ms[0].plugin)
            assertEquals("fold", ms[0].kind)
            assertTrue(ms[0].estAfter < ms[0].estBefore)
            val text = out.content()
            val id = ms[0].refId!!
            assertTrue(text.startsWith("$JSON_TREE_PREFIX${shortId(id)}]"), text)
            assertFalse(TOON_PREFIX in text)
            assertFalse(id in text)
            assertEquals(64, id.length)
            assertEquals(text, filter(toolReq(design()), Ctx(cx)).second.content(), "a second pass replays the stored pointer")
            assertEquals(prettyText(design()), cx.store.getArchive(cx.store.archiveIdsWithPrefix(shortId(id), 2).single(), cx.config.core.archiveDir)!!.decodeToString())
        }

    @Test fun liveZoneTurnsAreUntouched() =
        withRuntime("jt-live", { c -> on(c).let { it.copy(plugins = it.plugins.copy(archive = it.plugins.archive.copy(keepTurns = 4u))) } }) { cx ->
            val p = prettyText(design())
            val (ms, fresh) = filter(toolMessages(p), Ctx(cx))
            assertEquals(2, ms.size)
            for (i in 0 until 6) {
                val t = fresh.content(2 * i)
                if (i < 2) assertTrue(t.startsWith(JSON_TREE_PREFIX), t) else assertEquals(p, t)
            }
            assertEquals(fresh, filter(toolMessages(p), Ctx(cx)).second, "a second pass replays the stored pointers")
        }

    @Test fun aToonTableIsNotFoldedOnTheProxy() =
        withRuntime("jt-toon", on) { cx ->
            val body = toolReq(table8())
            val (ms, out) = filter(body, Ctx(cx))
            assertTrue(ms.isEmpty())
            assertEquals(body, out)
            val (tms, tout) = filter(out, Ctx(cx), ToonPlugin)
            assertTrue(tms.isNotEmpty(), "toon still encodes the table")
            assertTrue(tout.content().startsWith(TOON_PREFIX))
            assertFalse(JSON_TREE_PREFIX in tout.content())
        }
}
