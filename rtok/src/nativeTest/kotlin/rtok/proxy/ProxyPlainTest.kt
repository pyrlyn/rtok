// Port of tests/proxy.rs, part 3: plain forward when a switch is off (T56), the semantic cache's hit, truncation
// and caller-identity cases (T452, T323), an upstream error's ledger row, and the tools rewrite (T595, T686).
@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import rtok.config.Config
import rtok.store.SerdeJson
import rtok.store.countKind
import rtok.store.countTokens
import rtok.store.getArchive
import rtok.store.listMeasurements
import rtok.store.measurementCount
import rtok.store.otelTokenTotals
import rtok.store.usageRows
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val MESSAGES = "/v1/messages"

private fun health(p: ProxyUnderTest) = parsed(call("GET", "${p.url}/health").text()).jsonObject

private fun plainServer(
    label: String,
    up: FakeUpstream,
    proxyOn: Boolean,
    coreOn: Boolean,
) = ProxyUnderTest.start("plain-$label", { c: Config -> c.copy(core = c.core.copy(enabled = coreOn)).proxy { copy(upstream = up.url, mode = "compress", enabled = proxyOn) } })

class ProxyPlainTest {
    private fun assertPlainForward(
        label: String,
        proxyOn: Boolean,
        coreOn: Boolean,
    ) {
        Live.ring.clear()
        val m = anthropicBody
        FakeUpstream(m).use { up ->
            plainServer(label, up, proxyOn, coreOn).use { p ->
                assertEquals(parsed("""{"ok":true,"mode":"passthrough","enabled":false,"recording":false}"""), JsonObject(health(p).filterKeys { it != "live" }), label)
                val r = postJson("${p.url}$MESSAGES", t53Request())
                assertEquals(200, r.status, label)
                assertContentEquals(proxyFixture("anthropic_messages_body.json"), r.body, label)
                assertEquals(1, m.hits, label)
                eventually("$label live ring") { Live.ring.snapshot().isNotEmpty() }
                platform.posix.usleep(50_000u)
                assertEquals(0, p.state.store.countKind("api_request"), "$label: no calls rows")
                assertEquals(
                    0,
                    p.state.store
                        .usageRows(T53_SESSION)
                        .size,
                    "$label: no usage rows",
                )
                assertEquals(0, p.state.store.measurementCount("archive"), "$label: no archive measurements")
                val live = Live.ring.snapshot().first { it.path == MESSAGES }
                assertEquals(200, live.status, label)
                assertTrue(parsed(call("GET", "${p.url}/live").text()).jsonArray.isNotEmpty(), "$label /live")
                assertTrue(Live.alerts(p.state.cfg).isNotEmpty(), "$label: persistent alert until re-enabled")
            }
        }
    }

    @Test fun proxyDisabledIsPlainForwardWithNoBookkeeping() = assertPlainForward("proxy-off", false, true)

    @Test fun coreDisabledIsPlainForwardWithNoBookkeeping() = assertPlainForward("core-off", true, false)

    /** `[plugins.proxy] enabled = false` is the usage-capture switch. */
    @Test fun pluginSwitchOffIsPlainForward() {
        val c = Config().rebasePaths(rtok.support.tmpDir("proxy-plugin-off"))
        val state = ProxyState.open(c.copy(plugins = c.plugins.copy(proxy = c.plugins.proxy.copy(enabled = false))))
        try {
            assertTrue(state.plain(), "the plugin switch must stop recording")
        } finally {
            state.close()
        }
    }

    @Test fun enabledCompressStillArchivesWhenFlagsOn() {
        FakeUpstream(anthropicBody).use { up ->
            plainServer("both-on", up, true, true).use { p ->
                assertEquals(parsed("""{"ok":true,"mode":"compress","enabled":true,"recording":true}"""), health(p))
                assertEquals(200, postJson("${p.url}$MESSAGES", t53Request()).status)
                val rows = p.usage(T53_SESSION)
                assertEquals(1, rows.size)
                assertTrue(toolResultContents(json(p.sent(rows[0])))[0].startsWith("[archived "), "compress still rewrites when enabled")
                assertTrue(p.state.store.measurementCount("archive") > 0)
                assertTrue(Live.alerts(p.state.cfg).isEmpty(), "no alert when enabled")
            }
        }
    }
}

private fun cacheServer(
    label: String,
    upstream: String,
) = ProxyUnderTest.start(
    "t323-$label",
    { c: Config ->
        c
            .copy(
                plugins =
                    c.plugins.copy(
                        proxy =
                            c.plugins.proxy.copy(
                                semanticCache =
                                    c.plugins.proxy.semanticCache
                                        .copy(enabled = true),
                            ),
                    ),
            ).proxy { copy(upstream = upstream) }
    },
)

class ProxyCacheTest {
    /** The second identical request is a cache hit: upstream sees one, both leave a usage row. */
    @Test fun proxyCacheHitRecordsUsageAndRequestBytes() {
        val m = anthropicBody
        FakeUpstream(m).use { up ->
            cacheServer("hit", up.url).use { p ->
                val body = t51Request()
                assertContentEquals(proxyFixture("anthropic_messages_body.json"), postJson("${p.url}$MESSAGES", body).body)
                p.usage(T51_SESSION)
                assertContentEquals(proxyFixture("anthropic_messages_body.json"), postJson("${p.url}$MESSAGES", body).body)
                assertEquals(1, m.hits)
                val rows = p.usage(T51_SESSION, 2)
                assertEquals(2, rows.size, "miss and hit each leave one usage row")
                for (u in rows) {
                    assertEquals(listOf(10L, 0, 0, 2), listOf(u.input, u.cacheCreate, u.cacheRead, u.output), "hit usage comes from the cached body")
                    assertEquals(T51_MODEL, u.model)
                    assertNotNull(u.callId)
                }
                assertEquals(body, p.sent(rows[0]).decodeToString(), "hit call_io keeps the request bytes")
                eventually("two tokens rows") { p.state.store.countTokens() >= 2 }
                assertEquals(2, p.state.store.countTokens())
                assertEquals(1, p.state.store.measurementCount("proxy"))
                assertEquals(
                    listOf(10L to 2L),
                    p.state.store
                        .otelTokenTotals()
                        .map { it.input to it.output },
                    "cache hit must not count as provider-billed",
                )
            }
        }
    }

    /** T323: a body cut off mid-stream is not a response; the next identical request reaches upstream again. */
    @Test fun proxyCacheNeverStoresATruncatedBody() {
        TruncatingUpstream(proxyFixture("anthropic_messages_body.json"), 40).use { up ->
            cacheServer("trunc", up.url).use { p ->
                val first = runCatching { postJson("${p.url}$MESSAGES", t51Request()) }.getOrNull()
                assertTrue(first == null || first.body.size < proxyFixture("anthropic_messages_body.json").size, "the client sees the cut")
                p.usage(T51_SESSION)
                platform.posix.usleep(100_000u)
                runCatching { postJson("${p.url}$MESSAGES", t51Request()) }
                assertEquals(2, up.hits.value, "the second request must reach upstream, not the cache")
            }
        }
    }

    /** T323: the key carries the caller and the response-changing headers. */
    @Test fun proxyCacheIsKeyedByCallerIdentity() {
        val m = anthropicBody
        FakeUpstream(m).use { up ->
            cacheServer("caller", up.url).use { p ->
                val cases =
                    listOf(
                        arrayOf("x-api-key" to "key-a"),
                        arrayOf("x-api-key" to "key-b"),
                        arrayOf("authorization" to "Bearer tok-c"),
                        arrayOf("x-api-key" to "key-a", "anthropic-beta" to "files-api-2025-04-14"),
                        arrayOf("x-api-key" to "key-a", "anthropic-version" to "2023-06-01"),
                    )
                cases.forEachIndexed { i, h ->
                    postJson("${p.url}$MESSAGES", t51Request(), *h)
                    p.usage(T51_SESSION, i + 1)
                    platform.posix.usleep(100_000u)
                }
                assertEquals(cases.size, m.hits)
                postJson("${p.url}$MESSAGES", t51Request(), *cases[0])
                assertEquals(cases.size, m.hits)
            }
        }
    }

    /** Connection refused → 502, yet a calls row and an all-zero usage row (no tokens row). */
    @Test fun proxyUpstreamErrorStillRecordsUsageRow() {
        ProxyUnderTest.start("t452-err", { it.proxy { copy(upstream = "http://127.0.0.1:1") } }).use { p ->
            assertEquals(502, postJson("${p.url}$MESSAGES", t51Request()).status)
            val rows = p.usage(T51_SESSION)
            assertEquals(1, rows.size, "exactly one usage row despite the error")
            assertEquals(listOf(0L, 0, 0, 0), listOf(rows[0].input, rows[0].cacheCreate, rows[0].cacheRead, rows[0].output))
            assertNotNull(rows[0].callId)
            assertEquals(1, p.state.store.countKind("api_request"))
            assertEquals(0, p.state.store.countTokens(), "no provider counters, no tokens row")
        }
    }
}

private const val T595_DESC = "Short one. This second sentence is far too long to keep under the cap."

private fun rewriting(
    label: String,
    edit: rtok.config.ToolsRewrite.() -> rtok.config.ToolsRewrite,
    proxy: rtok.config.Proxy.() -> rtok.config.Proxy,
) = ProxyUnderTest.start(label, { c: Config -> c.proxy { proxy().let { it.copy(toolsRewrite = it.toolsRewrite.edit()) } } })

class ProxyToolsRewriteTest {
    @Test fun anthropicDropsDeniedToolButStillForwards() {
        FakeUpstream(anthropicBody).use { up ->
            rewriting("t595-a", { copy(enabled = true, maxDescriptionTokens = 5u, deny = listOf("drop_me")) }, { copy(upstream = up.url) }).use { p ->
                val body =
                    """{"model":"$T51_MODEL","max_tokens":8,"tools":[{"name":"Bash","description":"$T595_DESC","input_schema":{"type":"object"}},""" +
                        """{"name":"drop_me","description":"Gone.","input_schema":{"type":"object"}}],"messages":[""" +
                        """{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"drop_me","input":{}}]},{"role":"user","content":"hi"}],""" +
                        """"metadata":{"user_id":"sess-t595-a"}}"""
                assertEquals(200, postJson("${p.url}$MESSAGES", body).status)
                val sent = json(p.sent(p.usage("sess-t595-a")[0]))
                val tools = sent["tools"]!!.jsonArray
                assertEquals(1, tools.size)
                assertEquals(JsonPrimitive("Bash"), tools[0].jsonObject["name"])
                assertEquals(JsonPrimitive("Short one."), tools[0].jsonObject["description"])
                assertEquals(parsed("""{"type":"object"}"""), tools[0].jsonObject["input_schema"])
                assertEquals(
                    JsonPrimitive("drop_me"),
                    sent["messages"]!!
                        .jsonArray[0]
                        .jsonObject["content"]!!
                        .jsonArray[0]
                        .jsonObject["name"],
                )
                assertEquals(
                    listOf("tools_rewrite"),
                    p.state.store
                        .listMeasurements("proxy")
                        .map { it.kind },
                )
            }
        }
    }

    /** T686: upstream gets the compact catalog, the measurement's `ref_id` expands to the original `tools[]`. */
    @Test fun compactSchemaArchivesTheOriginalCatalog() {
        FakeUpstream(anthropicBody).use { up ->
            rewriting("t686-a", { copy(enabled = true, compactSchema = true) }, { copy(upstream = up.url) }).use { p ->
                val catalog = parsed(rtok.sys.readToString("${rtok.config.envVar("RTOK_FIXTURES")}/mcp_catalog_github.json")).jsonObject
                val original = catalog["tools"]!!
                val body =
                    JsonObject(
                        catalog + mapOf("model" to JsonPrimitive(T51_MODEL), "max_tokens" to JsonPrimitive(8), "metadata" to parsed("""{"user_id":"sess-t686-a"}""")),
                    )
                assertEquals(200, postJson("${p.url}$MESSAGES", SerdeJson.encode(body)).status)
                val sent = json(p.sent(p.usage("sess-t686-a")[0]))
                val before = SerdeJson.encode(original).length
                val after = SerdeJson.encode(sent["tools"]!!).length
                assertTrue(after * 10 < before * 7, "$before -> $after")
                val ms = p.state.store.listMeasurements("proxy")
                assertEquals(1, ms.size)
                assertEquals(
                    original,
                    parsed(
                        p.state.store
                            .getArchive(ms[0].refId!!, null)!!
                            .decodeToString(),
                    ),
                )
            }
        }
    }

    @Test fun openaiChatTruncatesFunctionDescription() {
        FakeUpstream(fixtureMock("/v1/chat/completions", "openai_chat_body.json", "application/json")).use { up ->
            rewriting("t595-o", { copy(enabled = true, maxDescriptionTokens = 5u) }, { copy(upstream = "http://127.0.0.1:1", openaiUpstream = up.url) }).use { p ->
                val params = """{"type":"object"}"""
                val body =
                    """{"model":"gpt-4o","user":"sess-t595-o","tools":[{"type":"function","function":{"name":"search","description":"$T595_DESC",""" +
                        """"parameters":$params}}],"messages":[{"role":"user","content":"hi"}]}"""
                assertEquals(200, postJson("${p.url}/v1/chat/completions", body).status)
                val fn = json(p.sent(p.usage("sess-t595-o")[0]))["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
                assertEquals(JsonPrimitive("Short one."), fn["description"])
                assertEquals(parsed(params), fn["parameters"])
                assertEquals(
                    listOf("tools_rewrite"),
                    p.state.store
                        .listMeasurements("proxy")
                        .map { it.kind },
                )
            }
        }
    }
}
