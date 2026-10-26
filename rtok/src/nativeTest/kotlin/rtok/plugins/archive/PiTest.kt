package rtok.plugins.archive

import kotlinx.serialization.json.*
import rtok.MIN_PREFIX
import rtok.plugin.sdk.*
import rtok.store.*
import rtok.support.*
import kotlin.test.*

/** A pi session array: user/assistant/toolResult triples, newest last, as in `~/.pi/agent/sessions`. */
private fun piArray(tags: List<String>) =
    buildJsonArray {
        tags.forEachIndexed { i, tag ->
            add(turnUser("prompt $i"))
            add(assistant("working…"))
            add(toolResult(tag))
        }
    }

private fun textParts(t: String) =
    buildJsonArray {
        addJsonObject {
            put("type", "text")
            put("text", t)
        }
    }

private fun turnUser(t: String) =
    buildJsonObject {
        put("role", "user")
        put("content", textParts(t))
    }

private fun assistant(t: String) =
    buildJsonObject {
        put("role", "assistant")
        put("content", textParts(t))
    }

private fun toolResult(
    tag: String,
    body: String = big(tag),
) = buildJsonObject {
    put("role", "toolResult")
    put("toolCallId", "call_$tag")
    put("toolName", "bash")
    put("content", textParts(body))
    put("isError", false)
}

private fun JsonElement.bytes() = toString().encodeToByteArray()

private fun ByteArray.json() = Json.parseToJsonElement(decodeToString()).jsonArray

private fun JsonArray.posOf(id: String) = indexOfFirst { (it.jsonObject["toolCallId"] as? JsonPrimitive)?.content == id }

private fun JsonElement.firstText() =
    jsonObject["content"]!!
        .jsonArray[0]
        .jsonObject["text"]!!
        .jsonPrimitive.content

class PiTest {
    @Test fun piViewYieldsToolResultsWithUserTurnDistance() {
        val refs = WireRequest(PiWire, piArray(listOf("a", "b", "c"))).toolResults()
        assertEquals(3, refs.size)
        assertEquals("call_a", refs[0].id)
        assertEquals(2, refs[0].turn, "oldest result sits two user turns back")
        assertEquals(0, refs[2].turn, "newest result is this turn")
        assertEquals(
            big("a"),
            refs[0]
                .content.jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
    }

    @Test fun nonArraysAndQuietArraysEchoTheInputBytes() =
        withRuntime("echo") { cx ->
            val ctx = Ctx(cx)
            val junk = "not json at all".encodeToByteArray()
            assertContentEquals(junk, rewriteStdin(junk, ctx))
            val obj = "{\"role\":\"user\"}".encodeToByteArray()
            assertContentEquals(obj, rewriteStdin(obj, ctx))
            val quiet = piArray(listOf("a", "b", "c")).bytes()
            assertContentEquals(quiet, rewriteStdin(quiet, ctx))
        }

    /** Three replays byte-identical; one new turn changes only the newly-aged block; every served pointer is measured. */
    @Test fun rewriteIsByteStableAndAgesOneBlockPerTurn() =
        withRuntime("stable") { cx ->
            val ctx = Ctx(cx)
            val count = { cx.store.measurementCount("archive") }
            val input = piArray(listOf("a", "b", "c", "d", "e")).bytes()
            val out1 = rewriteStdin(input, ctx)
            assertFalse(out1.contentEquals(input), "the oldest result is outside keep_turns = 4")
            assertEquals(1L, count())
            val out2 = rewriteStdin(out1, ctx)
            val out3 = rewriteStdin(out2, ctx)
            assertContentEquals(out1, out2)
            assertContentEquals(out2, out3)
            assertEquals(3L, count())
            val grown = JsonArray(out3.json() + listOf(turnUser("prompt 5"), assistant("…"), toolResult("f")))
            val before = out3.json()
            val after = rewriteStdin(grown.bytes(), ctx).json()
            val changed = before.indices.filter { before[it] != after[it] }
            assertEquals(listOf(before.posOf("call_b")), changed, "only the newly-aged block changed")
            assertTrue(after[before.posOf("call_a")].firstText().startsWith("[archived "))
            for (tag in listOf("a", "b")) {
                val text = after[after.posOf("call_$tag")].firstText()
                val id = text.substringAfter("expand(").substringBefore(')')
                assertEquals(MIN_PREFIX, id.length, id)
                val full = cx.store.archiveIdsWithPrefix(id, 2).single()
                assertEquals(big(tag), ctx.getArchive(full)!!.decodeToString())
            }
            assertEquals(5L, count(), "grown call serves two pointers: the persisted one and the newly-aged one")
        }

    @Test fun smallResultsStayWhole() =
        withRuntime("small") { cx ->
            val msgs = JsonArray(listOf(turnUser("prompt 0"), assistant("working…"), toolResult("a", "tiny")))
            val input = msgs.bytes()
            assertContentEquals(input, rewriteStdin(input, Ctx(cx)))
            assertEquals(0L, cx.store.measurementCount("archive"))
        }
}
