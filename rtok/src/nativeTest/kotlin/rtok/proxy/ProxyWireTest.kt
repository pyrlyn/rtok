// Port of tests/proxy.rs, part 1 (T5.0–T5.1, T11.2, T11.3, T51.3): each wire's body and stream pass through
// byte-identical, and the ledger gets one usage row with the provider's four counters, the call, its call_io
// and the provider tokens row.
@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.Config
import rtok.store.SerdeJson
import rtok.store.UsageRow
import rtok.store.archiveDecision
import rtok.store.callIoRequest
import rtok.store.countCallIo
import rtok.store.countKind
import rtok.store.countTokens
import rtok.store.measurementCount
import rtok.store.modelSlugOfCall
import rtok.store.putArchive
import rtok.store.putArchiveDecision
import rtok.store.usageRows
import rtok.support.tmpDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val T51_MODEL = "claude-sonnet-4-20250514"
internal const val T51_SESSION = "sess-t51"
private const val T112_SESSION = "sess-t112"
private const val T112_MODEL = "gpt-4o"
private const val T113_SESSION = "sess-t113"
private const val T113_MODEL = "gpt-4.1"
private const val T113_COLLIDING_CALL = "call-shared-across-wires"
private const val T113_NEW_CALL = "call-responses-only"
private const val T513_SESSION = "sess-t513"
private const val T513_MODEL = "gemini-2.0-flash"
private const val T513_GENERATE = "/v1beta/models/gemini-2.0-flash:generateContent"
private const val T513_STREAM = "/v1beta/models/gemini-2.0-flash:streamGenerateContent"

internal val anthropicBody get() = fixtureMock("/v1/messages", "anthropic_messages_body.json", "application/json")

internal fun t51Request() = """{"model":"$T51_MODEL","max_tokens":8,"messages":[{"role":"user","content":"hi"}],"metadata":{"user_id":"$T51_SESSION"}}"""

internal fun t51Server(
    label: String,
    up: FakeUpstream,
    mode: String,
) = ProxyUnderTest.start("t51-$label", { it.proxy { copy(upstream = up.url, mode = mode) } })

/** The Anthropic upstream must go unused: a request reaching the fixture proves the OpenAI upstream was picked. */
internal fun openaiServer(
    label: String,
    up: FakeUpstream,
    mode: String,
) = ProxyUnderTest.start("t11-$label", { it.proxy { copy(upstream = "http://127.0.0.1:1", openaiUpstream = up.url, mode = mode) } })

internal fun ProxyUnderTest.usage(
    session: String,
    n: Int = 1,
): List<UsageRow> {
    eventually("usage row for $session") { state.store.usageRows(session).size >= n }
    return state.store.usageRows(session)
}

internal fun ProxyUnderTest.sent(row: UsageRow): ByteArray = state.store.callIoRequest(row.callId!!.toInt())!!

private fun counters(u: UsageRow) = listOf(u.input, u.cacheCreate, u.cacheRead, u.output)

private fun bigOutput() = (1..400).joinToString("\n") { "old line $it: some tool output with words" }

class ProxyWireTest {
    @Test fun theFixtureUpstreamServesEachFixtureOnce() {
        val cases =
            listOf(
                Triple("/v1/messages", "anthropic_messages_body.json", "application/json"),
                Triple("/v1/messages", "anthropic_messages_stream.json", "text/event-stream"),
                Triple("/v1/chat/completions", "openai_chat_body.json", "application/json"),
                Triple("/v1/chat/completions", "openai_chat_stream.json", "text/event-stream"),
                Triple("/v1/responses", "openai_responses_body.json", "application/json"),
                Triple("/v1/responses", "openai_responses_stream.json", "text/event-stream"),
            )
        for ((path, file, type) in cases) {
            val m = fixtureMock(path, file, type)
            FakeUpstream(m).use { up ->
                assertContentEquals(proxyFixture(file), postJson("${up.url}$path", """{"model":"test"}""").body, file)
                assertEquals(1, m.hits, file)
            }
        }
    }

    @Test fun proxyPassthroughBodyRecordsUsageRows() {
        val m = anthropicBody
        FakeUpstream(m).use { up ->
            t51Server("body", up, "passthrough").use { p ->
                val r = postJson("${p.url}/v1/messages", t51Request())
                assertEquals(200, r.status)
                assertContentEquals(proxyFixture("anthropic_messages_body.json"), r.body)
                assertEquals(1, m.hits)
                val rows = p.usage(T51_SESSION)
                assertEquals(1, rows.size, "exactly one usage row")
                assertEquals(listOf(10L, 0, 0, 2), counters(rows[0]))
                assertEquals(T51_MODEL, rows[0].model)
                assertEquals(T51_MODEL, p.state.store.modelSlugOfCall(rows[0].callId!!.toInt()), "models.slug must equal the request model")
                assertEquals(1, p.state.store.countKind("api_request"))
                assertEquals(1, p.state.store.countCallIo())
                eventually("tokens row") { p.state.store.countTokens() >= 1 }
                assertEquals(1, p.state.store.countTokens())
            }
        }
    }

    @Test fun upstreamRequestAsksForIdentityEncoding() {
        val m =
            Mock(
                { it.path == "/v1/messages" && it.headers["accept-encoding"] == listOf("identity") },
                headers = listOf("content-type" to "application/json"),
                bytes = listOf(proxyFixture("anthropic_messages_body.json")),
            )
        FakeUpstream(m).use { up ->
            ProxyUnderTest.start("identity", { it.proxy { copy(upstream = up.url) } }).use { p ->
                assertEquals(200, postJson("${p.url}/v1/messages", t51Request(), "accept-encoding" to "gzip, deflate, br").status)
                assertEquals(1, m.hits)
                assertEquals(1, p.usage(T51_SESSION).size, "usage recorded despite the client's gzip offer")
            }
        }
    }

    @Test fun proxyPassthroughStreamIsByteIdenticalAndRecordsUsage() {
        val m = fixtureMock("/v1/messages", "anthropic_messages_stream.json", "text/event-stream")
        FakeUpstream(m).use { up ->
            t51Server("stream", up, "passthrough").use { p ->
                val r = postJson("${p.url}/v1/messages", t51Request())
                assertEquals(200, r.status)
                assertTrue(r.headers.any { it.first.equals("content-type", true) && "text/event-stream" in it.second }, "SSE content-type must pass through")
                assertContentEquals(proxyFixture("anthropic_messages_stream.json"), r.body)
                val rows = p.usage(T51_SESSION)
                assertEquals(1, rows.size)
                assertEquals(listOf(0L, 0, 0, 2), counters(rows[0]), "usage merged from SSE message_delta")
                assertEquals(1, p.state.store.countKind("api_request"))
                assertEquals(1, p.state.store.countCallIo())
            }
        }
    }

    @Test fun proxyHealthReportsOkAndMode() {
        FakeUpstream(anthropicBody).use { up ->
            t51Server("health", up, "passthrough").use { p ->
                val r = call("GET", "${p.url}/health")
                assertEquals(200, r.status)
                assertEquals(SerdeJson.parse("""{"ok":true,"mode":"passthrough","enabled":true,"recording":true}"""), SerdeJson.parse(r.text()))
            }
        }
    }

    @Test fun proxyOpenaiChatBodyRecordsUsageWithCachedTokens() {
        val m = fixtureMock("/v1/chat/completions", "openai_chat_body.json", "application/json")
        FakeUpstream(m).use { up ->
            openaiServer("chat-body", up, "passthrough").use { p ->
                val r = postJson("${p.url}/v1/chat/completions", """{"model":"$T112_MODEL","user":"$T112_SESSION","messages":[{"role":"user","content":"hi"}]}""")
                assertEquals(200, r.status)
                assertContentEquals(proxyFixture("openai_chat_body.json"), r.body)
                assertEquals(1, m.hits)
                val rows = p.usage(T112_SESSION)
                assertEquals(1, rows.size)
                assertEquals(listOf(3L, 0, 7, 2), counters(rows[0]))
                assertEquals(T112_MODEL, rows[0].model)
            }
        }
    }

    @Test fun proxyOpenaiChatStreamIsByteIdenticalAndAddsIncludeUsage() {
        FakeUpstream(fixtureMock("/v1/chat/completions", "openai_chat_stream.json", "text/event-stream")).use { up ->
            openaiServer("chat-stream", up, "passthrough").use { p ->
                val r = postJson("${p.url}/v1/chat/completions", """{"model":"$T112_MODEL","user":"$T112_SESSION","stream":true,"messages":[{"role":"user","content":"hi"}]}""")
                assertEquals(200, r.status)
                assertContentEquals(proxyFixture("openai_chat_stream.json"), r.body)
                val u = p.usage(T112_SESSION)[0]
                assertEquals(listOf(3L, 0, 7, 2), counters(u), "usage decoded from the final SSE chunk")
                val sent = SerdeJson.parse(p.sent(u).decodeToString()) as JsonObject
                assertEquals(JsonPrimitive(true), (sent["stream_options"] as JsonObject)["include_usage"])
                assertEquals(SerdeJson.parse("""[{"role":"user","content":"hi"}]"""), sent["messages"], "nothing else rewritten")
            }
        }
    }

    @Test fun proxyOpenaiResponsesBodyRecordsUsageWithoutRewritingPreviousResponse() {
        val m = fixtureMock("/v1/responses", "openai_responses_body.json", "application/json")
        FakeUpstream(m).use { up ->
            openaiServer("responses-body", up, "compress").use { p ->
                val request =
                    """{"model":"$T113_MODEL","user":"$T113_SESSION","previous_response_id":"resp_previous","input":[""" +
                        """{"type":"function_call_output","call_id":"call-old","output":${SerdeJson.encode(JsonPrimitive(bigOutput()))}},""" +
                        """{"role":"user","content":"one"},{"role":"user","content":"two"},{"role":"user","content":"three"}]}"""
                val r = postJson("${p.url}/v1/responses", request)
                assertEquals(200, r.status)
                assertContentEquals(proxyFixture("openai_responses_body.json"), r.body)
                assertEquals(1, m.hits)
                val u = p.usage(T113_SESSION)[0]
                assertEquals(listOf(3L, 0, 7, 2), counters(u))
                assertEquals(T113_MODEL, u.model)
                assertEquals(request, p.sent(u).decodeToString(), "previous_response_id must produce zero rewrites")
                assertEquals(0, p.state.store.measurementCount("archive"))
            }
        }
    }

    @Test fun proxyOpenaiResponsesCompressIsByteExactAndIgnoresArchiveDecisions() {
        FakeUpstream(fixtureMock("/v1/responses", "openai_responses_body.json", "application/json")).use { up ->
            openaiServer("responses-compress", up, "compress").use { p ->
                val s = p.state.store
                val archiveId = s.putArchive("other-session", "unrelated output".encodeToByteArray(), tmpDir("t113-collision"))
                val collision = "[archived unrelated]"
                s.putArchiveDecision(T113_COLLIDING_CALL, archiveId, "other-session", collision)
                val output = SerdeJson.encode(JsonPrimitive(bigOutput()))
                val request =
                    """{  "user" : "$T113_SESSION", "input" : [
          { "type" : "function_call_output", "call_id" : "$T113_COLLIDING_CALL", "output" : $output },
          { "type" : "function_call_output", "call_id" : "$T113_NEW_CALL", "output" : $output },
          { "role" : "user", "content" : "one" },
          { "role" : "user", "content" : "two" },
          { "role" : "user", "content" : "three" }
        ], "model" : "$T113_MODEL" }"""
                val r = postJson("${p.url}/v1/responses", request)
                assertEquals(200, r.status)
                assertContentEquals(proxyFixture("openai_responses_body.json"), r.body)
                val u = p.usage(T113_SESSION)[0]
                assertEquals(request, p.sent(u).decodeToString(), "T11.3 must preserve noncanonical Responses JSON byte-for-byte")
                assertEquals(collision, s.archiveDecision("other-session", T113_COLLIDING_CALL)!!.pointer)
                assertNull(s.archiveDecision(T113_SESSION, T113_COLLIDING_CALL))
                assertNull(s.archiveDecision(T113_SESSION, T113_NEW_CALL))
                assertEquals(0, s.measurementCount("archive"))
            }
        }
    }

    @Test fun proxyOpenaiResponsesStreamIsByteIdenticalAndRecordsUsage() {
        val m = fixtureMock("/v1/responses", "openai_responses_stream.json", "text/event-stream")
        FakeUpstream(m).use { up ->
            openaiServer("responses-stream", up, "passthrough").use { p ->
                val request = """{"model":"$T113_MODEL","user":"$T113_SESSION","stream":true,"input":"hi"}"""
                val r = postJson("${p.url}/v1/responses", request)
                assertEquals(200, r.status)
                assertContentEquals(proxyFixture("openai_responses_stream.json"), r.body)
                assertEquals(1, m.hits)
                val u = p.usage(T113_SESSION)[0]
                assertEquals(listOf(3L, 0, 7, 2), counters(u))
                assertEquals(request, p.sent(u).decodeToString(), "Responses requests need no passthrough shaping")
            }
        }
    }
}

/** Gemini (T51.3): the session rides `x-rtok-session`, the model comes from the path. */
class ProxyGeminiTest {
    private fun gemini(
        label: String,
        up: FakeUpstream,
    ) = ProxyUnderTest.start("t513-$label", { c: Config -> c.proxy { copy(upstream = "http://127.0.0.1:1", geminiUpstream = up.url, mode = "passthrough") } })

    private val hi = """{"contents":[{"role":"user","parts":[{"text":"hi"}]}]}"""

    @Test fun proxyGeminiBodyRecordsUsageWithCachedTokensAndPathModel() {
        val m = fixtureMock(T513_GENERATE, "gemini_generate_body.json", "application/json")
        FakeUpstream(m).use { up ->
            gemini("generate-body", up).use { p ->
                val r = postJson("${p.url}$T513_GENERATE", hi, "x-rtok-session" to T513_SESSION)
                assertEquals(200, r.status)
                assertContentEquals(proxyFixture("gemini_generate_body.json"), r.body)
                assertEquals(1, m.hits)
                val rows = p.usage(T513_SESSION)
                assertEquals(1, rows.size)
                assertEquals(listOf(5L, 0, 7, 3), counters(rows[0]))
                assertEquals(T513_MODEL, rows[0].model)
                assertEquals("gemini", rows[0].api)
                assertEquals(1, p.state.store.countKind("api_request"))
                assertEquals(1, p.state.store.countCallIo())
                eventually("tokens row") { p.state.store.countTokens() >= 1 }
                assertEquals(1, p.state.store.countTokens())
            }
        }
    }

    @Test fun proxyGeminiStreamIsByteIdenticalAndRecordsUsage() {
        val m = fixtureMock(T513_STREAM, "gemini_generate_stream.json", "text/event-stream")
        FakeUpstream(m).use { up ->
            gemini("generate-stream", up).use { p ->
                val r = postJson("${p.url}$T513_STREAM", hi, "x-rtok-session" to T513_SESSION)
                assertEquals(200, r.status)
                assertTrue(r.headers.any { it.first.equals("content-type", true) && "text/event-stream" in it.second })
                assertContentEquals(proxyFixture("gemini_generate_stream.json"), r.body)
                assertEquals(1, m.hits)
                val rows = p.usage(T513_SESSION)
                assertEquals(1, rows.size)
                assertEquals(listOf(5L, 0, 7, 3), counters(rows[0]))
                assertEquals(T513_MODEL, rows[0].model)
            }
        }
    }
}
