// Port of the `#[cfg(test)]` cases of src/proxy/mod.rs and src/proxy/cli.rs: path routing, loopback-only
// `/live`, hop counting (T442), the semantic cache in front of a fake upstream, compress-mode noise stripping,
// streaming SSE through with usage recorded after the stream, and query keys kept out of errors and logs.
@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.http.HttpRequest
import rtok.http.HttpResponse
import rtok.http.HttpSettings
import rtok.http.ktor.KtorHttp
import rtok.store.SerdeJson
import rtok.store.countCalls
import rtok.store.insertCall
import rtok.store.logsAfter
import rtok.store.measurementCount
import rtok.store.upsertSession
import rtok.store.usageRows
import rtok.store.usageSessions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val USAGE_BODY = """{"type":"message","usage":{"input_tokens":1,"output_tokens":2}}"""
private val JSON = listOf("content-type" to "application/json")

private fun post(
    url: String,
    body: String,
    vararg headers: Pair<String, String>,
): HttpResponse = KtorHttp(HttpSettings()).use { c -> runBlocking { c.post(HttpRequest(url, JSON + headers, body.encodeToByteArray())) } }

private fun get(url: String): HttpResponse = KtorHttp(HttpSettings()).use { c -> runBlocking { c.get(HttpRequest(url)) } }

private fun messages(path: String = "/v1/messages") = Mock({ it.method == "POST" && it.path == path }, headers = JSON, chunks = listOf(USAGE_BODY))

private fun noisyRequest(content: String) =
    SerdeJson.encode(
        buildJsonObject {
            put("model", "claude-test")
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "tool_result")
                                            put("tool_use_id", "t1")
                                            put("content", content)
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        },
    )

private fun ProxyUnderTest.logs() = state.store.logsAfter(0, 100)

private fun hop(
    incoming: String?,
    outgoing: String,
    passthrough: Boolean,
) {
    val noisy = noisyRequest("\u001b[31mFAIL\u001b[0m")
    val m =
        Mock(
            { it.path == "/v1/messages" && it.header(ProxyHttp.HOP_HEADER) == outgoing && (if (passthrough) it.body == noisy else '\u001b' !in it.body) },
            headers = JSON,
            chunks = listOf(USAGE_BODY),
        )
    FakeUpstream(m).use { up ->
        ProxyUnderTest.start("hop-$outgoing-$passthrough", { it.proxy { copy(upstream = up.url, mode = "compress") } }).use { p ->
            val extra = listOfNotNull(incoming?.let { ProxyHttp.HOP_HEADER to it }).toTypedArray()
            assertEquals(200, post("${p.url}/v1/messages", noisy, *extra).status)
            assertEquals(1, m.hits, up.seen.joinToString { it.headers.toString() })
            val warned = { p.logs().any { it.level == "warn" && "rtok proxy hop" in it.message } }
            eventually("hop bookkeeping") { (p.state.store.countCalls() == 0L) == passthrough && warned() == passthrough }
        }
    }
}

class ProxyServerTest {
    @Test fun openaiBatchPathsAreToldFromAnthropicOnes() {
        val anthropic = listOf("anthropic-version" to "2023-06-01")
        assertTrue(ProxyHttp.isOpenaiBatchPath("/v1/batches", emptyList()))
        assertTrue(ProxyHttp.isOpenaiBatchPath("/v1/batches/batch_1/cancel", emptyList()))
        assertTrue(ProxyHttp.isOpenaiBatchPath("/v1/files/file-1/content", emptyList()))
        // Anthropic's Files API shares the path and is told apart by its mandatory header.
        assertFalse(ProxyHttp.isOpenaiBatchPath("/v1/files", anthropic))
        assertFalse(ProxyHttp.isOpenaiBatchPath("/v1/messages/batches", emptyList()))
        assertFalse(ProxyHttp.isOpenaiBatchPath("/v1/batchesx", emptyList()))
    }

    @Test fun onlyLoopbackPeersReadLiveCalls() {
        for (p in listOf(null, "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1")) assertTrue(ProxyHttp.peerIsLocal(p), "$p")
        for (p in listOf("192.168.1.9", "2001:db8::1", "[2001:db8::1]")) assertFalse(ProxyHttp.peerIsLocal(p), p)
    }

    @Test fun hopCountsReadJunkAsOneHop() {
        assertEquals(0, ProxyHttp.hopsOf(emptyList()))
        assertEquals(2, ProxyHttp.hopsOf(listOf(ProxyHttp.HOP_HEADER to " 2 ")))
        assertEquals(1, ProxyHttp.hopsOf(listOf("X-Rtok-Proxied" to "yes")))
    }

    @Test fun proxyLogRowsKeepTheirShapeThroughTheFunnel() {
        ProxyUnderTest.start("log").use { p ->
            val s = p.state.store
            s.upsertSession("sess", null, null, null, null)
            val call = s.insertCall("sess", "proxy", "api_request", null, null, null, null, null)
            p.state.log("sess", call, "error", "boom")
            val r = p.logs().single()
            assertEquals(listOf("error", "module", "proxy", "boom"), listOf(r.level, r.source, r.name, r.message))
            assertEquals("sess", r.session)
            assertEquals(call, r.callId)
            assertNull(r.plugin)
        }
    }

    @Test fun healthAndLiveAnswerOnTheirOwnRoutes() {
        ProxyUnderTest.start("health").use { p ->
            assertEquals("""{"enabled":true,"mode":"passthrough","ok":true,"recording":true}""", get("${p.url}/health").text())
            val live = get("${p.url}/live")
            assertEquals(200, live.status)
            assertTrue(live.text().startsWith("["), live.text())
            assertEquals(405, post("${p.url}/health", "{}").status)
        }
        ProxyUnderTest.start("health-plain", { it.proxy { copy(enabled = false) } }).use { p ->
            val h = SerdeJson.parse(get("${p.url}/health").text()) as JsonObject
            assertEquals(JsonPrimitive("passthrough"), h["mode"])
            assertEquals(JsonPrimitive(false), h["recording"])
        }
    }
}

/** The forwarding path against a fake upstream. */
class ProxyForwardTest {
    @Test fun semanticCacheDisabledProxyIsBytesIdentical() {
        val m = messages()
        FakeUpstream(m).use { up ->
            ProxyUnderTest.start("sc-off", { it.proxy { copy(upstream = up.url) } }).use { p ->
                val req = """{"model":"claude-test","messages":[{"role":"user","content":"hi"}]}"""
                repeat(2) { assertEquals(USAGE_BODY, post("${p.url}/v1/messages", req).text()) }
                assertEquals(2, m.hits)
            }
        }
    }

    @Test fun semanticCacheEnabledDirectHitSkipsUpstream() {
        val m = messages()
        FakeUpstream(m).use { up ->
            val tune = { c: rtok.config.Config ->
                val pp = c.plugins.proxy
                c.proxy { copy(upstream = up.url) }.let { it.copy(plugins = it.plugins.copy(proxy = pp.copy(semanticCache = pp.semanticCache.copy(enabled = true)))) }
            }
            ProxyUnderTest.start("sc-on", tune).use { p ->
                val req = """{"model":"claude-test","messages":[{"role":"user","content":"hi"}]}"""
                assertEquals(USAGE_BODY, post("${p.url}/v1/messages", req).text())
                // The cache fills after the stream ends; the usage row is written after it.
                eventually("first usage row") {
                    p.state.store
                        .usageSessions()
                        .isNotEmpty()
                }
                assertEquals(USAGE_BODY, post("${p.url}/v1/messages", req).text())
                assertEquals(1, m.hits)
                eventually("cache-hit measurement") { p.state.store.measurementCount("proxy") == 1L }
                // T45.2: the cache hit writes a usage row of its own.
                eventually("two usage rows") {
                    p.state.store
                        .usageSessions()
                        .sumOf {
                            p.state.store
                                .usageRows(it)
                                .size
                        } == 2
                }
            }
        }
    }

    @Test fun compressModeStripsTerminalNoiseByteStably() {
        val noisy = "\u001b[31mFAIL\u001b[0m a\u200bb\u0007  \r\n"
        val want = noisyRequest("FAIL ab  \r\n")
        val m = Mock({ it.path == "/v1/messages" && it.body == want }, headers = JSON, chunks = listOf(USAGE_BODY))
        FakeUpstream(m).use { up ->
            ProxyUnderTest.start("noise", { it.proxy { copy(upstream = up.url, mode = "compress") } }).use { p ->
                repeat(2) { assertEquals(200, post("${p.url}/v1/messages", noisyRequest(noisy)).status, up.seen.lastOrNull()?.body) }
                assertEquals(2, m.hits)
            }
        }
    }

    @Test fun upstreamBaseWithTrailingPathForwardsToTarget() {
        val m = messages("/prefix/v1/messages")
        FakeUpstream(m).use { up ->
            ProxyUnderTest.start("prefix", { it.proxy { copy(upstream = "${up.url}/prefix") } }).use { p ->
                assertEquals(200, post("${p.url}/v1/messages", """{"model":"test"}""").status)
                assertEquals(1, m.hits)
            }
        }
    }

    @Test fun hopMarkerCountsAndSkipsShapingOnALaterPass() {
        hop(null, "1", false)
        hop("2", "3", true)
        hop("yes", "2", true)
    }

    @Test fun hopMarkerAtTheCapAnswersLoopDetected() {
        val m = Mock({ true })
        FakeUpstream(m).use { up ->
            ProxyUnderTest.start("hop-cap", { it.proxy { copy(upstream = up.url, mode = "compress") } }).use { p ->
                assertEquals(508, post("${p.url}/v1/messages", """{"model":"claude-test"}""", ProxyHttp.HOP_HEADER to "${ProxyHttp.MAX_HOPS}").status)
                assertEquals(0, m.hits)
                eventually("proxy.upstream error row") { p.logs().any { it.level == "error" && "proxy.upstream" in it.message } }
            }
        }
    }

    @Test fun upstreamErrorsNeverCarryTheQueryKey() {
        // A port nothing listens on: bind one, then let it go.
        val free = Served({ _, _ -> rtok.http.server.StreamingResponse(200) }, "free-port").use { it.addr }
        ProxyUnderTest.start("query-key", { it.proxy { copy(geminiUpstream = "http://$free") } }).use { p ->
            val r = post("${p.url}/v1beta/models/m:generateContent?key=SECRETKEY", """{"contents":[]}""")
            assertEquals(502, r.status)
            assertFalse("SECRETKEY" in r.text(), r.text())
            eventually("upstream error row") { p.logs().any { "upstream POST" in it.message } }
            for (row in p.logs()) assertFalse("SECRETKEY" in row.message, row.message)
        }
    }

    @Test fun sseStreamsThroughAndUsageLandsAfterTheStream() {
        val events =
            listOf(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}}\n\n",
                "event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":7}}\n\n",
            )
        val m = Mock({ it.path == "/v1/messages" }, headers = listOf("content-type" to "text/event-stream"), chunks = events)
        FakeUpstream(m).use { up ->
            ProxyUnderTest.start("sse", { it.proxy { copy(upstream = up.url) } }).use { p ->
                val body = """{"model":"claude-test","stream":true,"messages":[{"role":"user","content":"hi"}]}"""
                val chunks =
                    KtorHttp(HttpSettings()).use { c ->
                        runBlocking {
                            c.stream("POST", HttpRequest("${p.url}/v1/messages", JSON, body.encodeToByteArray())) { r ->
                                assertEquals("text/event-stream", r.header("content-type"))
                                buildList { while (true) add((r.read() ?: break).decodeToString()) }
                            }
                        }
                    }
                assertEquals(events.joinToString(""), chunks.joinToString(""))
                eventually("usage row") {
                    p.state.store
                        .usageSessions()
                        .isNotEmpty()
                }
                val u =
                    p.state.store
                        .usageRows(
                            p.state.store
                                .usageSessions()
                                .single(),
                        ).single()
                assertEquals(5L to 7L, u.input to u.output)
            }
        }
    }
}
