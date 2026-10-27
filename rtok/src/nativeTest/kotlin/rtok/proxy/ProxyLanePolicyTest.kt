// Port of tests/proxy_lane_policy.rs (T385.2): in `compress` mode the non-agent lanes go out byte for byte, the
// agent lane is rewritten exactly as before lanes existed, one lane switch opens one rewrite, toon needs its own
// switch, the semantic cache serves the agent lane only and per upstream target, a stream cut inside a character
// keeps its usage, and a lane timeout bounds only its lane.
@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.Config
import rtok.store.SerdeJson
import rtok.store.measurementCount
import rtok.store.usageRows
import rtok.store.usageSessions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val LP_SESSION = "sess-lane-policy"
private const val LP_DESCRIPTION =
    "Runs a shell command and returns its output. It accepts a working directory, a timeout and an environment, streams long " +
        "output back in chunks, and reports the exit status separately. Prefer it over ad hoc scripts, and keep each command short " +
        "and idempotent whenever possible."
private const val ANSWER = """{"type":"message","usage":{"input_tokens":1,"output_tokens":2}}"""
private const val HI = """{"model":"claude-test","messages":[{"role":"user","content":"hi"}]}"""

private fun turns(content: (Int) -> String): String =
    (1..6).joinToString(",") { t ->
        """{"role":"user","content":[{"type":"tool_result","tool_use_id":"tu-$t","content":${SerdeJson.encode(JsonPrimitive(content(t)))}}]},""" +
            """{"role":"assistant","content":[{"type":"tool_use","id":"tu-${t + 1}","name":"Bash","input":{}}]}"""
    }

private fun lpRequest(): String {
    val raw =
        """{"model":"claude-test","max_tokens":8,"system":"sys","tools":[{"name":"Bash","description":${SerdeJson.encode(JsonPrimitive(LP_DESCRIPTION))},""" +
            """"input_schema":{"type":"object"}}],"messages":[${turns { t -> (1..400).joinToString("\n") { "t$t line $it: some shell output with words" } }}],"metadata":{"user_id":"$LP_SESSION"}}"""
    return rtok.measure.stats.SerdeJson
        .pretty(SerdeJson.parse(raw)!!)
}

private fun tabularRequest(): String {
    val table = (1..8).joinToString(",", "[", "]") { """{"a":$it,"b":$it,"c":$it}""" }
    return """{"model":"claude-test","max_tokens":8,"messages":[${turns { table }}],"metadata":{"user_id":"sess-lane-toon"}}"""
}

private fun allSwitchesOn(
    c: Config,
    base: String,
): Config {
    val p = c.proxy { copy(upstream = base, openaiUpstream = base, geminiUpstream = base, mode = "compress", toolsRewrite = toolsRewrite.copy(enabled = true), contextManagement = true) }
    return p.copy(plugins = p.plugins.copy(toon = p.plugins.toon.copy(enabled = true)))
}

private fun lpPost(
    url: String,
    path: String,
    lane: String?,
    body: String,
) = call("POST", "$url$path", body, *listOfNotNull("content-type" to "application/json", lane?.let { ProxyHttp.LANE_HEADER to it }).toTypedArray())

private fun sink() = Mock({ true }, headers = listOf("content-type" to "application/json"), chunks = listOf("{}"))

private fun FakeUpstream.last() = seen.last().let { it.body to (it.header("anthropic-beta") != null) }

private fun ProxyUnderTest.usageCount() = state.store.usageSessions().sumOf { state.store.usageRows(it).size }

private fun Config.cacheOn() = copy(plugins = plugins.copy(proxy = plugins.proxy.copy(semanticCache = plugins.proxy.semanticCache.copy(enabled = true))))

private fun JsonObject.description() = (((this["tools"] as JsonArray)[0] as JsonObject)["description"] as JsonPrimitive).content

class ProxyLanePolicyTest {
    @Test fun nonAgentLanesAreForwardedByteForByteInCompressMode() {
        FakeUpstream(sink()).use { up ->
            ProxyUnderTest.start("lane-policy-bytes", { allSwitchesOn(it, up.url) }).use { p ->
                val body = lpRequest()
                val cases =
                    listOf(
                        "/v1/messages" to "bulk",
                        "/lane/internal/v1/messages" to null,
                        "/v1/messages/count_tokens" to null,
                        "/v1/embeddings" to null,
                        "/v1/messages/batches" to null,
                        "/v1/messages/batches" to "bulk",
                        "/v1/files" to null,
                    )
                for ((path, lane) in cases) {
                    assertTrue(ProxyHttp.isSuccess(lpPost(p.url, path, lane, body).status))
                    val (sent, beta) = up.last()
                    assertEquals(body, sent, "$path $lane was rewritten")
                    assertFalse(beta, "$path $lane carries the context-management beta")
                }
            }
        }
    }

    @Test fun agentLaneIsRewrittenAsBeforeLanesExisted() {
        val (reference, referenceBeta) =
            FakeUpstream(sink()).use { before ->
                ProxyUnderTest.start("lane-policy-agent-off", { allSwitchesOn(it, before.url).proxy { copy(lanes = lanes.copy(enabled = false)) } }).use { p ->
                    assertTrue(ProxyHttp.isSuccess(lpPost(p.url, "/v1/messages", null, lpRequest()).status))
                }
                before.last()
            }
        FakeUpstream(sink()).use { up ->
            ProxyUnderTest.start("lane-policy-agent-on", { allSwitchesOn(it, up.url) }).use { p ->
                for (lane in listOf(null, "agent")) {
                    assertTrue(ProxyHttp.isSuccess(lpPost(p.url, "/v1/messages", lane, lpRequest()).status))
                    val (sent, beta) = up.last()
                    assertNotEquals(lpRequest(), sent, "the agent lane must still be rewritten")
                    assertEquals(reference, sent, "agent bytes drifted ($lane)")
                    assertEquals(referenceBeta, beta)
                    assertTrue(beta, "the agent lane must still arm context management")
                }
                val sent = SerdeJson.parse(up.last().first) as JsonObject
                assertTrue(sent["context_management"] is JsonObject)
                assertTrue(sent.description().length < LP_DESCRIPTION.length)
            }
        }
    }

    @Test fun aLaneSwitchOpensExactlyOneRewrite() {
        FakeUpstream(sink()).use { up ->
            val tune = { c: Config ->
                allSwitchesOn(c, up.url).proxy { copy(lanes = lanes.copy(bulk = lanes.bulk.copy(toolsRewrite = true), internal = lanes.internal.copy(contextManagement = true))) }
            }
            ProxyUnderTest.start("lane-policy-opt-in", tune).use { p ->
                assertTrue(ProxyHttp.isSuccess(lpPost(p.url, "/v1/messages", "bulk", lpRequest()).status))
                val (bulkSent, bulkBeta) = up.last()
                val b = SerdeJson.parse(bulkSent) as JsonObject
                assertTrue(!bulkBeta && b["context_management"] == null)
                assertTrue(b.description().length < LP_DESCRIPTION.length, "bulk opted in to tools_rewrite")
                val first = ((((b["messages"] as JsonArray)[0] as JsonObject)["content"] as JsonArray)[0] as JsonObject)["content"] as JsonPrimitive
                assertFalse(first.content.startsWith("[archived "), "bulk did not opt in to compress")
                assertTrue(ProxyHttp.isSuccess(lpPost(p.url, "/v1/messages", "internal", lpRequest()).status))
                val (intSent, intBeta) = up.last()
                val i = SerdeJson.parse(intSent) as JsonObject
                assertTrue(intBeta && i["context_management"] is JsonObject)
                assertEquals(LP_DESCRIPTION, i.description())
            }
        }
    }

    @Test fun toonNeedsItsOwnLaneSwitchInsideACompressingLane() {
        FakeUpstream(sink()).use { up ->
            val tune = { c: Config ->
                allSwitchesOn(c, up.url).proxy { copy(lanes = lanes.copy(bulk = lanes.bulk.copy(compress = true), internal = lanes.internal.copy(compress = true, toon = true))) }
            }
            ProxyUnderTest.start("lane-policy-toon", tune).use { p ->
                assertTrue(ProxyHttp.isSuccess(lpPost(p.url, "/v1/messages", "bulk", tabularRequest()).status))
                assertEquals(0, p.state.store.measurementCount("toon"))
                assertTrue(ProxyHttp.isSuccess(lpPost(p.url, "/v1/messages", "internal", tabularRequest()).status))
                assertTrue(p.state.store.measurementCount("toon") > 0)
            }
        }
    }

    @Test fun theSemanticCacheServesTheAgentLaneOnlyByDefault() {
        val m = Mock({ it.method == "POST" && it.path == "/v1/messages" }, headers = listOf("content-type" to "application/json"), chunks = listOf(ANSWER))
        FakeUpstream(m).use { up ->
            ProxyUnderTest.start("lane-policy-cache", { it.proxy { copy(upstream = up.url) }.cacheOn() }).use { p ->
                for (n in 1..2) {
                    assertTrue(ProxyHttp.isSuccess(lpPost(p.url, "/v1/messages", "bulk", HI).status))
                    eventually("$n usage rows") { p.usageCount() >= n }
                }
                assertEquals(2, m.hits)
                assertTrue(ProxyHttp.isSuccess(lpPost(p.url, "/v1/messages", null, HI).status))
                eventually("3 usage rows") { p.usageCount() >= 3 }
                assertTrue(ProxyHttp.isSuccess(lpPost(p.url, "/v1/messages", null, HI).status))
                assertEquals(3, m.hits)
            }
        }
    }

    @Test fun cachedResponsesAreScopedToTheUpstreamTarget() {
        val answer = { from: String -> """{"type":"message","from":"$from","usage":{"input_tokens":1,"output_tokens":2}}""" }
        val ma = Mock({ it.path == "/v1/messages" }, headers = listOf("content-type" to "application/json"), chunks = listOf(answer("a")))
        val mb = Mock({ it.path == "/v1/messages" }, headers = listOf("content-type" to "application/json"), chunks = listOf(answer("b")))
        FakeUpstream(ma).use { a ->
            FakeUpstream(mb).use { b ->
                val tune = { c: Config -> c.proxy { copy(upstream = a.url, lanes = lanes.copy(internal = lanes.internal.copy(upstream = b.url, semanticCache = true))) }.cacheOn() }
                ProxyUnderTest.start("lane-policy-cache-target", tune).use { p ->
                    val text = { lane: String? -> lpPost(p.url, "/v1/messages", lane, HI).text() }
                    assertTrue(""""from":"a"""" in text(null))
                    eventually("usage 1") { p.usageCount() >= 1 }
                    assertTrue(""""from":"b"""" in text("internal"), "the warm entry for upstream A must not answer a request bound for B")
                    eventually("usage 2") { p.usageCount() >= 2 }
                    assertEquals(1 to 1, ma.hits to mb.hits)
                    assertTrue(""""from":"a"""" in text(null))
                    assertTrue(""""from":"b"""" in text("internal"))
                    assertEquals(1 to 1, ma.hits to mb.hits)
                }
            }
        }
    }

    @Test fun aStreamCutInsideACharacterKeepsItsEarlierUsage() {
        val start = """{"type":"message_start","message":{"usage":{"input_tokens":100,"output_tokens":1,"service_tier":"standard"}}}"""
        val stream = "event: message_start\ndata: $start\n\n".encodeToByteArray() + "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"".encodeToByteArray() + byteArrayOf(0xc3.toByte())
        val m = Mock({ it.path == "/v1/messages" }, headers = listOf("content-type" to "text/event-stream"), bytes = listOf(stream))
        FakeUpstream(m).use { up ->
            ProxyUnderTest.start("lane-policy-utf8-tail", { it.proxy { copy(upstream = up.url) } }).use { p ->
                val body = """{"model":"claude-test","max_tokens":8,"metadata":{"user_id":"$LP_SESSION"},"messages":[{"role":"user","content":"hi"}]}"""
                val r = lpPost(p.url, "/v1/messages", null, body)
                assertTrue(stream.contentEquals(r.body), "stream bytes changed")
                eventually("usage row") {
                    p.state.store
                        .usageRows(LP_SESSION)
                        .isNotEmpty()
                }
                val row = p.state.store.usageRows(LP_SESSION)[0]
                assertEquals(100L to 1L, row.input to row.output)
            }
        }
    }

    @Test fun aLaneTimeoutBoundsOnlyThatLane() {
        val m = Mock({ it.path == "/v1/messages" }, chunks = listOf("{}"), delayMs = 2500)
        FakeUpstream(m).use { up ->
            ProxyUnderTest.start("lane-policy-timeout", { it.proxy { copy(upstream = up.url, lanes = lanes.copy(bulk = lanes.bulk.copy(timeoutS = 1u))) } }).use { p ->
                val body = """{"model":"claude-test","messages":[]}"""
                assertEquals(502, lpPost(p.url, "/v1/messages", "bulk", body).status)
                assertTrue(ProxyHttp.isSuccess(lpPost(p.url, "/v1/messages", null, body).status))
            }
        }
    }
}
