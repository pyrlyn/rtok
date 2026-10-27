// Port of tests/proxy_flex.rs (T385.5): `service_tier = "flex"` on the bulk and internal lanes only, a client
// tier respected unless forced, and the three `on_429` policies against a scripted upstream.
@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.Config
import rtok.http.server.ServerRequest
import rtok.http.server.StreamingHandler
import rtok.http.server.StreamingResponse
import rtok.store.SerdeJson
import kotlin.concurrent.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FLEX_CHAT = "/v1/chat/completions"
private const val FLEX_BODY = "{ \"model\" : \"gpt-test\",\n  \"user\": \"sess-x\", \"messages\": [] }"

/** Answers the n-th request with `script[n]` (the last entry thereafter), keeping every body. */
private class Scripted(
    private val script: List<Int>,
    private val retryAfter: String? = null,
) : StreamingHandler {
    private val seen = AtomicReference<List<String>>(emptyList())
    val bodies: List<String> get() = seen.value

    override suspend fun handle(
        req: ServerRequest,
        peer: String?,
    ): StreamingResponse {
        var n: Int
        while (true) {
            val cur = seen.value
            n = cur.size
            if (seen.compareAndSet(cur, cur + req.body.decodeToString())) break
        }
        val status = script.getOrElse(n) { script.last() }
        val extra = listOfNotNull(retryAfter?.takeIf { status == 429 }?.let { "retry-after" to it })
        return ProxyHttp.bytes(status, "application/json", "{}".encodeToByteArray(), extra)
    }
}

private fun <T> flex(
    tag: String,
    up: Scripted,
    tune: (Config) -> Config = { it },
    body: (String) -> T,
): T =
    Served(up, "scripted-$tag").use { s ->
        val base = { c: Config ->
            c.proxy { copy(openaiUpstream = s.url, upstream = s.url, lanes = lanes.copy(bulk = lanes.bulk.copy(flex = true), internal = lanes.internal.copy(flex = true)), flex = flex.copy(backoffMs = 1u)) }
        }
        ProxyUnderTest.start(tag, { tune(base(it)) }).use { p -> body(p.url) }
    }

private fun post(
    url: String,
    path: String,
    lane: String?,
    body: String,
): Int {
    val h = listOfNotNull("content-type" to "application/json", lane?.let { ProxyHttp.LANE_HEADER to it }).toTypedArray()
    return call("POST", "$url$path", body, *h).status
}

private fun tier(s: String) = (SerdeJson.parse(s) as JsonObject)["service_tier"]

private fun withoutTier(s: String) = JsonObject((SerdeJson.parse(s) as JsonObject) - "service_tier")

private fun Config.on429(
    policy: String,
    force: Boolean = false,
    retries: UInt = 3u,
) = proxy { copy(flex = flex.copy(on429 = policy, force = force, retries = retries)) }

private val FLEX = JsonPrimitive("flex")

class ProxyFlexTest {
    @Test fun flexIsSetOnBulkAndInternalOnly() {
        val up = Scripted(listOf(200))
        flex("flex-lanes", up) { url ->
            for (lane in listOf("bulk", "internal")) assertEquals(200, post(url, FLEX_CHAT, lane, FLEX_BODY))
            assertEquals(200, post(url, "/lane/bulk/v1/responses", null, FLEX_BODY))
            for ((i, sent) in up.bodies.withIndex()) {
                assertEquals(FLEX, tier(sent), "request $i")
                assertTrue(sent.endsWith(FLEX_BODY.substring(1)), "request $i: $sent")
            }
            for (lane in listOf(null, "agent")) {
                assertEquals(200, post(url, FLEX_CHAT, lane, FLEX_BODY))
                assertEquals(FLEX_BODY, up.bodies.last())
            }
        }
    }

    @Test fun aLaneWithoutTheSwitchAndOtherWiresAreUntouched() {
        val up = Scripted(listOf(200))
        flex("flex-off", up, { it.proxy { copy(lanes = lanes.copy(bulk = lanes.bulk.copy(flex = false))) } }) { url ->
            assertEquals(200, post(url, FLEX_CHAT, "bulk", FLEX_BODY))
            assertEquals(FLEX_BODY, up.bodies.last())
            val anthropic = """{"model":"claude-test","messages":[]}"""
            assertEquals(200, post(url, "/v1/messages", "internal", anthropic))
            assertEquals(anthropic, up.bodies.last())
            assertEquals(200, post(url, FLEX_CHAT, "internal", "[1]"))
            assertEquals("[1]", up.bodies.last())
        }
    }

    @Test fun aClientTierIsRespectedUnlessForced() {
        val up = Scripted(listOf(200))
        flex("flex-respect", up) { url ->
            for (t in listOf("auto", "default", "priority", "flex")) {
                val body = """{"model":"m","service_tier":"$t"}"""
                assertEquals(200, post(url, FLEX_CHAT, "bulk", body))
                assertEquals(body, up.bodies.last(), "$t respected")
            }
        }
        val forced = Scripted(listOf(200))
        flex("flex-force", forced, { it.on429("none", force = true) }) { url ->
            val body = """{"model":"m","service_tier":"priority","n":1}"""
            assertEquals(200, post(url, FLEX_CHAT, "bulk", body))
            val sent = forced.bodies.last()
            assertEquals(FLEX, tier(sent))
            assertEquals(withoutTier(body), withoutTier(sent))
        }
    }

    @Test fun policyNoneHandsThe429ToTheClient() {
        val up = Scripted(listOf(429, 200))
        flex("flex-none", up) { url ->
            assertEquals(429, post(url, FLEX_CHAT, "bulk", FLEX_BODY))
            assertEquals(1, up.bodies.size)
        }
    }

    @Test fun backoffRetriesOnFlexUntilItSucceedsOrRunsOut() {
        val up = Scripted(listOf(429, 429, 200))
        flex("flex-backoff", up, { it.on429("backoff") }) { url ->
            assertEquals(200, post(url, FLEX_CHAT, "bulk", FLEX_BODY))
            val sent = up.bodies
            assertEquals(3, sent.size)
            assertTrue(sent.all { it == sent[0] && tier(it) == FLEX })
        }
        val never = Scripted(listOf(429))
        flex("flex-backoff-out", never, { it.on429("backoff", retries = 2u) }) { url ->
            assertEquals(429, post(url, FLEX_CHAT, "bulk", FLEX_BODY))
            assertEquals(3, never.bodies.size, "one try and two retries")
        }
    }

    @Test fun defaultRetriesOnceOnAutoAndChangesOnlyTheTier() {
        val up = Scripted(listOf(429, 200))
        flex("flex-default", up, { it.on429("default") }) { url ->
            assertEquals(200, post(url, FLEX_CHAT, "internal", FLEX_BODY))
            val sent = up.bodies
            assertEquals(2, sent.size)
            assertEquals(FLEX, tier(sent[0]))
            assertEquals(JsonPrimitive("auto"), tier(sent[1]))
            assertEquals(sent[0].replace("flex", "auto"), sent[1])
            assertTrue(sent[1].endsWith(FLEX_BODY.substring(1)))
        }
        val limited = Scripted(listOf(429))
        flex("flex-default-limit", limited, { it.on429("default") }) { url ->
            assertEquals(429, post(url, FLEX_CHAT, "bulk", FLEX_BODY))
            assertEquals(2, limited.bodies.size)
        }
    }

    @Test fun aForcedOverAClientTierFallsBackToItAndAClientFlexIsNotRetried() {
        val up = Scripted(listOf(429, 200))
        flex("flex-force-default", up, { it.on429("default", force = true) }) { url ->
            assertEquals(200, post(url, FLEX_CHAT, "bulk", """{"model":"m","service_tier":"priority"}"""))
            assertEquals(listOf(FLEX, JsonPrimitive("priority")), up.bodies.map(::tier))
        }
        val none = Scripted(listOf(429, 200))
        flex("flex-force-none", none, { it.on429("default", force = true) }) { url ->
            assertEquals(200, post(url, FLEX_CHAT, "bulk", """{"model":"m"}"""))
            assertEquals(listOf(FLEX, JsonPrimitive("auto")), none.bodies.map(::tier))
        }
        val own = Scripted(listOf(429, 200))
        flex("flex-client-owned", own, { it.on429("default") }) { url ->
            val body = """{"model":"m","service_tier":"flex"}"""
            assertEquals(429, post(url, FLEX_CHAT, "bulk", body))
            assertEquals(listOf(body), own.bodies)
        }
    }

    @Test fun aRetryAfterOverTheCapIsNotWaitedFor() {
        val a = Scripted(listOf(429), "3600")
        flex("flex-ra-backoff", a, { it.on429("backoff") }) { url ->
            assertEquals(429, post(url, FLEX_CHAT, "bulk", FLEX_BODY))
            assertEquals(1, a.bodies.size)
        }
        val b = Scripted(listOf(429, 200), "3600")
        flex("flex-ra-default", b, { it.on429("default") }) { url ->
            assertEquals(200, post(url, FLEX_CHAT, "bulk", FLEX_BODY))
            assertEquals(2, b.bodies.size)
        }
        val c = Scripted(listOf(429, 200), "Wed, 21 Oct 2099 07:28:00 GMT")
        flex("flex-ra-date", c, { it.on429("backoff") }) { url ->
            assertEquals(200, post(url, FLEX_CHAT, "bulk", FLEX_BODY))
            assertEquals(2, c.bodies.size)
        }
    }
}
