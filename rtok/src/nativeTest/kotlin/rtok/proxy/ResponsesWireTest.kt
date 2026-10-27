@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.*
import kotlin.test.*

/** Port of the Wire tests in src/proxy/openai_responses.rs. */
class ResponsesWireTest {
    @Test fun responsesKeysFunctionCallOutputsByCallIdAndCountsLaterUserTurns() {
        val r =
            req(
                OpenAiResponsesWire,
                """{"input":[
            {"role":"user","content":"one"},
            {"type":"function_call_output","call_id":"a","output":"old"},
            {"role":"user","content":"two"},
            {"type":"function_call_output","call_id":"b","output":"live"}]}""",
            ).toolResults()
        assertEquals(listOf("a" to 1, "b" to 0), r.map { it.id to it.turn })
    }

    @Test fun responsesYieldNothingWhenHistoryIsServerSide() =
        assertTrue(
            req(
                OpenAiResponsesWire,
                """{"previous_response_id":"resp_1","input":[{"type":"function_call_output","call_id":"a","output":"x"}]}""",
            ).toolResults().isEmpty(),
        )

    @Test fun responsesReadUsageFromBodyAndFromResponseCompleted() {
        val expected = Usage(3, 0, 7, 2)
        val usage = """{"input_tokens":10,"input_tokens_details":{"cached_tokens":7},"output_tokens":2}"""
        assertEquals(expected, OpenAiResponsesWire.usageFromBody(js("""{"usage":$usage}""")))
        assertEquals(expected, OpenAiResponsesWire.usageFromSse(js("""{"type":"response.completed","response":{"usage":$usage}}""")))
        assertNull(OpenAiResponsesWire.usageFromSse(js("""{"type":"response.created","response":{}}""")))
        assertNull(OpenAiResponsesWire.usageFromSse(js("""{"type":"response.created","response":{"usage":$usage}}""")))
    }

    @Test fun responsesTerminalEventsIncompleteAndFailedCarryUsageToo() {
        val usage = """{"input_tokens":10,"output_tokens":2}"""
        for (kind in listOf("response.incomplete", "response.failed")) {
            assertEquals(Usage(10, 0, 0, 2), OpenAiResponsesWire.usageFromSse(js("""{"type":"$kind","response":{"usage":$usage}}""")), kind)
        }
        assertNull(OpenAiResponsesWire.usageFromSse(js("""{"type":"response.failed","response":{"usage":null}}""")))
    }
}
