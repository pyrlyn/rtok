package rtok.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.MIN_PREFIX
import rtok.hook.MAX_FRAME
import rtok.plugins.cmd.Settings
import rtok.store.listMeasurements
import rtok.support.withRuntime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WrapTest {
    private val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}""".encodeToByteArray()

    private fun reader(stream: ByteArray) = FrameReader().also { it.feed(stream) }

    private fun FrameReader.expect(
        framing: Framing,
        body: ByteArray,
    ) {
        val f = assertNotNull(next(true))
        assertEquals(framing, f.framing)
        assertContentEquals(body, f.body, f.body.decodeToString())
    }

    private fun obj(s: String) = Json.parseToJsonElement(s)

    @Test fun readsBothFramingsFromOneStream() {
        val stream =
            "\r\n".encodeToByteArray() + FrameReader.encode(Framing.Line, ping) + FrameReader.encode(Framing.Header, ping) +
                "content-length: 2\r\nX-Other: y\r\n\r\n{}".encodeToByteArray()
        val r = reader(stream)
        r.expect(Framing.Line, ping)
        r.expect(Framing.Header, ping)
        r.expect(Framing.Header, "{}".encodeToByteArray())
        assertNull(r.next(true))
    }

    @Test fun framesFedOneByteAtATimeMatchTheWholeStream() {
        val stream = FrameReader.encode(Framing.Header, ping) + FrameReader.encode(Framing.Line, ping)
        val r = FrameReader()
        val got = mutableListOf<Frame>()
        for (b in stream) {
            r.feed(byteArrayOf(b))
            r.next(false)?.let(got::add)
        }
        assertEquals(listOf(Framing.Header, Framing.Line), got.map { it.framing })
        got.forEach { assertContentEquals(ping, it.body) }
        assertNull(r.next(true))
    }

    @Test fun oversizedContentLengthIsRawAndDoesNotSwallowTheNextFrame() {
        for (len in listOf((MAX_FRAME.toULong() + 1u).toString(), ULong.MAX_VALUE.toString(), "340282366920938463463374607431768211455")) {
            val head = "Content-Length: $len\r\n\r\n".encodeToByteArray()
            val r = reader(head + ping + "\n".encodeToByteArray())
            r.expect(Framing.Raw, head)
            r.expect(Framing.Line, ping)
            assertNull(r.next(true), len)
        }
    }

    @Test fun contentLengthAtTheCapStillReadsAShortBody() {
        val head = "Content-Length: $MAX_FRAME\r\n\r\n".encodeToByteArray()
        val r = reader(head + "{}".encodeToByteArray())
        assertNull(r.next(false), "a short body waits for more bytes while the pipe is open")
        r.expect(Framing.Raw, head + "{}".encodeToByteArray())
        assertNull(r.next(true))
    }

    @Test fun invalidUtf8InAHeaderIsForwardedNotEof() {
        val stream = "Content-Length: 2\r\nX-Bin: ".encodeToByteArray() + byteArrayOf(0xff.toByte()) + "\r\n\r\n{}{\"ok\":1}\n".encodeToByteArray()
        val r = reader(stream)
        assertNotNull(r.next(true), "header bytes must not be dropped as EOF")
        assertNotNull(r.next(true), "the following JSON frame must still be readable")
    }

    @Test fun headerWithoutContentLengthResyncsAtTheNextFrame() {
        val head = "content-length: nope\r\nX-Other: y\r\n\r\n".encodeToByteArray()
        val r = reader(head + FrameReader.encode(Framing.Header, ping))
        r.expect(Framing.Raw, head)
        r.expect(Framing.Header, ping)
        assertNull(r.next(true))
    }

    @Test fun pendingMatchesToolCallIdsOnly() {
        val p = Wrap.Pending()
        p.note(obj("""{"id":7,"method":"tools/call","params":{"name":"grep"}}"""))
        p.note(obj("""{"id":8,"method":"tools/list"}"""))
        assertNull(p.take("""{"id":8,"result":{}}""".encodeToByteArray()))
        assertNull(p.take("""{"id":"7","result":{}}""".encodeToByteArray()))
        assertEquals("grep", p.take("""{"id":7,"result":{}}""".encodeToByteArray()))
        assertNull(p.take("""{"id":7,"result":{}}""".encodeToByteArray()))
    }

    @Test fun serverRequestsDoNotConsumeClientPendingCalls() {
        val p = Wrap.Pending()
        p.note(obj("""{"id":7,"method":"tools/call","params":{"name":"grep"}}"""))
        for (frame in listOf(
            """{"id":7,"method":"sampling/createMessage","params":{}}""",
            """{"id":"7","method":"roots/list"}""",
            """{"method":"notifications/progress","params":{"id":7}}""",
        )) {
            assertNull(p.take(frame.encodeToByteArray()), frame)
        }
        assertEquals("grep", p.take("""{"id":7,"result":{}}""".encodeToByteArray()))
        p.note(obj("""{"id":9,"method":"tools/call","params":{"name":"find"}}"""))
        assertEquals("find", p.take("""{"id":9,"error":{"code":-1,"message":"x"}}""".encodeToByteArray()))
        assertNull(p.take("""{"id":9,"result":{}}""".encodeToByteArray()))
    }

    @Test fun drainReturnsEveryUnansweredRequestOnce() {
        val p = Wrap.Pending()
        p.note(obj("""{"id":1,"method":"initialize"}"""))
        p.note(obj("""{"id":"2","method":"tools/call","params":{"name":"grep"}}"""))
        p.note(obj("""{"method":"notifications/initialized"}"""))
        assertNull(p.take("""{"id":1,"result":{}}""".encodeToByteArray()))
        assertEquals(listOf("\"2\""), p.drain().map { it.toString() })
        assertEquals(emptyList(), p.drain())
        assertNull(p.take("""{"id":"2","result":{}}""".encodeToByteArray()))
    }

    @Test fun shortenResultRecordsArchiveMcpAboveThreshold() {
        withRuntime("wrap-mcp") { cx ->
            val settings = Settings.fromConfig(cx.config)
            val text = (1..200).joinToString("") { "line $it\n" }
            val result = obj("""{"content":[{"type":"text","text":${Json.encodeToString(text)}}]}""").jsonObject
            val cut = assertNotNull(Wrap.shortenResult(cx, settings, "linear", "list", result, "archive", "mcp"))
            val printed =
                cut["content"]!!
                    .jsonArray[0]
                    .jsonObject["text"]!!
                    .jsonPrimitive.content
            assertTrue("expand: rtok expand " in printed, printed)
            assertTrue(printed.length < text.length)
            val shown = printed.substringAfterLast("rtok expand ").trimEnd(']')
            assertEquals(MIN_PREFIX, shown.length, shown)
            cx.flushMeasurements()
            val rows = cx.store.listMeasurements("archive")
            assertEquals(1, rows.size)
            assertEquals("mcp", rows[0].kind)
            val full = rows[0].refId!!.substringAfterLast(':')
            assertEquals(64, full.length, full)
            assertTrue(full.startsWith(shown), "$full $shown")
            val small = obj("""{"content":[{"type":"text","text":"hi\n"}]}""").jsonObject
            assertNull(Wrap.shortenResult(cx, settings, "linear", "list", small, "archive", "mcp"))
        }
    }

    @Test fun anErrorResultIsNeverCut() {
        withRuntime("wrap-err") { cx ->
            val text = (1..200).joinToString("") { "line $it\n" }
            val result = obj("""{"isError":true,"content":[{"type":"text","text":${Json.encodeToString(text)}}]}""").jsonObject
            assertNull(Wrap.shortenResult(cx, Settings.fromConfig(cx.config), "s", "t", result, "cmd", "wrap"))
        }
    }
}
