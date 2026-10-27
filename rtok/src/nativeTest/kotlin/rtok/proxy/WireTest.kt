@file:Suppress("MagicNumber")

package rtok.proxy

import kotlin.test.*

/** Port of src/proxy/wire.rs tests. */
class WireTest {
    private val sse = "text/event-stream"

    @Test fun serviceTierComesFromTheResponseOnEveryShape() {
        fun json(v: String) = Responses.serviceTier(null, v.encodeToByteArray())
        assertEquals("flex", json("""{"service_tier":"flex"}"""))
        assertEquals("batch", json("""{"usage":{"service_tier":"batch"}}"""))
        assertNull(json("""{"usage":{"input_tokens":1}}"""))
        assertNull(json("not json"))

        fun events(vararg e: String) = Responses.serviceTier(sse, e.joinToString("") { "data: $it\n\n" }.encodeToByteArray())
        assertEquals(
            "flex",
            events(
                """{"type":"response.created","response":{"service_tier":"auto"}}""",
                """{"type":"response.completed","response":{"service_tier":"flex"}}""",
            ),
        )
        assertEquals("standard", events("""{"type":"message_start","message":{"usage":{"service_tier":"standard"}}}"""))
        assertEquals("default", events("""{"service_tier":"default","choices":[]}"""))
        assertNull(events("""{"choices":[]}"""))
    }

    /** T584: a capture cut inside a multibyte character keeps the complete events before it. */
    @Test fun aSplitUtf8TailKeepsTheCompleteUsageAndTierEvents() {
        val wire = Wires.forPath("/v1/messages")!!
        val start = """{"type":"message_start","message":{"usage":{"input_tokens":100,"output_tokens":1,"service_tier":"standard"}}}"""
        val head = "event: message_start\ndata: $start\n\n".encodeToByteArray()

        fun read(b: ByteArray) = Responses.usage(wire, sse, b) to Responses.serviceTier(sse, b)
        read(head).let { (u, t) ->
            assertEquals(100L, u?.input)
            assertEquals("standard", t)
        }
        for (ch in listOf("é", "€", "😀")) {
            val delta = "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"$ch\"}}\n\n".encodeToByteArray()
            val chBytes = ch.encodeToByteArray()
            val at = (0..delta.size - chBytes.size).first { i -> chBytes.indices.all { delta[i + it] == chBytes[it] } }
            for (cut in 1 until chBytes.size) {
                val body = head + delta.copyOfRange(0, at + cut)
                val (u, t) = read(body)
                assertEquals(100L to 1L, u!!.input to u.output, "$ch cut $cut")
                assertEquals("standard", t, "$ch cut $cut")
            }
        }
        val end = "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":9}}\n\n".encodeToByteArray()
        val bad = head + "data: ".encodeToByteArray() + byteArrayOf(0xff.toByte(), 0xfe.toByte(), 10, 10) + end
        assertEquals(100L to 9L, read(bad).first?.let { it.input to it.output })
        assertEquals(null to null, read("data: {\"type\":\"ping\"}\n\ndata: ".encodeToByteArray() + byteArrayOf(0xc3.toByte())))
        val truncated = "{\"usage\":{\"input_tokens\":1,\"x\":\"".encodeToByteArray() + byteArrayOf(0xc3.toByte())
        assertNull(Responses.usage(wire, null, truncated))
    }

    @Test fun intFieldAcceptsWholeNumberFloats() {
        val usage = js("""{"input_tokens":10.0,"output_tokens":2}""")
        assertEquals(10, intField(usage, "input_tokens"))
        assertEquals(2, intField(usage, "output_tokens"))
        assertEquals(0, intField(usage, "missing"))
    }

    @Test fun wireReportsOwnProviderAndApi() {
        val cases =
            listOf(
                Triple("/v1/messages", Wires.ANTHROPIC_PROVIDER, Wires.API_ANTHROPIC),
                Triple("/v1/chat/completions", Wires.OPENAI_PROVIDER, Wires.API_OPENAI_CHAT),
                Triple("/v1/responses", Wires.OPENAI_PROVIDER, Wires.API_OPENAI_RESPONSES),
                Triple("/v1beta/models/gemini-2.0-flash:generateContent", Wires.GEMINI_PROVIDER, Wires.API_GEMINI),
                Triple("/v1beta/models/gemini-2.0-flash:streamGenerateContent", Wires.GEMINI_PROVIDER, Wires.API_GEMINI),
            )
        for ((path, provider, api) in cases) {
            val w = Wires.forPath(path)!!
            assertEquals(provider, w.provider, path)
            assertEquals(api, w.api, path)
        }
    }

    @Test fun joinUpstreamKeepsPercentEncodedPathVerbatim() =
        assertEquals(
            "http://127.0.0.1/prefix/v1/models/a%20b%2Fc:generate",
            Upstream.join("http://127.0.0.1/prefix", "/v1/models/a%20b%2Fc:generate", null),
        )

    @Test fun joinUpstreamRefusesDotSegments() {
        for (p in listOf("/../x", "/v1/../../x", "/%2e%2e/x", "/.%2E/x", "/./v1", "/%2E/v1")) {
            assertFailsWith<IllegalArgumentException>(p) { Upstream.join("https://gw/team-a", p, null) }
        }
        assertEquals("https://gw/team-a/v1/a..b/.x", Upstream.join("https://gw/team-a", "/v1/a..b/.x", null))
    }

    @Test fun joinUpstreamAvoidsDoubledSlashesAndQueries() {
        assertEquals("http://127.0.0.1/v1/messages", Upstream.join("http://127.0.0.1", "/v1/messages", null))
        assertEquals("http://127.0.0.1/prefix/v1/messages", Upstream.join("http://127.0.0.1/prefix", "/v1/messages", null))
        assertEquals("http://127.0.0.1/prefix/v1/messages?q=1", Upstream.join("http://127.0.0.1/prefix?keep=1", "/v1/messages", "q=1"))
    }
}
