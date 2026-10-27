@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.*
import kotlin.test.*

/** Port of the Wire tests in src/proxy/openai_chat.rs. */
class ChatWireTest {
    @Test fun chatCountsUserTurnsAfterEachToolMessage() {
        val r =
            req(
                OpenAiChatWire,
                """{"messages":[
            {"role":"user","content":"hi"},
            {"role":"assistant","tool_calls":[{"id":"one"}]},
            {"role":"tool","tool_call_id":"one","content":"old"},
            {"role":"user","content":"more"},
            {"role":"assistant","tool_calls":[{"id":"two"}]},
            {"role":"tool","tool_call_id":"two","content":"live"}]}""",
            ).toolResults()
        assertEquals(listOf("one" to 1, "two" to 0), r.map { it.id to it.turn })
    }

    @Test fun chatReadsUsageFromBodyAndFinalStreamChunk() {
        val usage = js("""{"usage":{"prompt_tokens":10,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":7}}}""")
        val expected = Usage(3, 0, 7, 2)
        assertEquals(expected, OpenAiChatWire.usageFromBody(usage))
        assertEquals(expected, OpenAiChatWire.usageFromSse(usage))
        assertNull(OpenAiChatWire.usageFromSse(js("""{"usage":null}""")))
        assertEquals(12, OpenAiChatWire.providerTotal(expected))
    }

    @Test fun chatAddsIncludeUsageOnlyToStreamingRequestsThatOmitIt() {
        val s = OpenAiChatWire.prepareRequest(js("""{"stream":true}"""), true)!!
        assertEquals(JsonPrimitive(true), s.jsonObject["stream_options"]!!.jsonObject["include_usage"])
        assertNull(OpenAiChatWire.prepareRequest(js("""{"stream":true,"stream_options":{"include_usage":false}}"""), true))
        assertNull(OpenAiChatWire.prepareRequest(js("""{"model":"gpt-4o"}"""), true))
        assertNull(OpenAiChatWire.prepareRequest(js("""{"stream":true}"""), false), "config can turn it off")
    }
}
