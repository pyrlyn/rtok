// Port of tests/proxy_lane_gate.rs (T385.7): a capped bulk lane holds its slots and a short line, turns the rest
// away with `429` + `Retry-After`, and never delays an agent turn; with the proxy switched off nothing is capped.
@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.http.server.ServerRequest
import rtok.http.server.StreamingHandler
import rtok.http.server.StreamingResponse
import rtok.store.SerdeJson
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals

private const val GATE_BODY = """{"model":"m","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""

/** An upstream that counts arrivals and answers only once released. */
private class Held : StreamingHandler {
    val arrived = AtomicInt(0)
    private val gate = Semaphore(1000, 1000)

    fun release(n: Int) = repeat(n) { gate.release() }

    override suspend fun handle(
        req: ServerRequest,
        peer: String?,
    ): StreamingResponse {
        arrived.incrementAndGet()
        gate.acquire()
        return ProxyHttp.bytes(200, "application/json", "{}".encodeToByteArray())
    }
}

private fun bulk(url: String) = call("POST", "$url/v1/messages", GATE_BODY, "content-type" to "application/json", ProxyHttp.LANE_HEADER to "bulk")

class ProxyLaneGateTest {
    @Test fun agentTurnIsNotQueuedBehindABulkBurst() {
        val answered = Mock({ true }, headers = listOf("content-type" to "application/json"), chunks = listOf("{}"))
        val held = Held()
        FakeUpstream(answered).use { agentUp ->
            Served(held, "held-upstream").use { bulkUp ->
                val tune = { c: rtok.config.Config ->
                    c.proxy { copy(upstream = agentUp.url, lanes = lanes.copy(bulk = lanes.bulk.copy(upstream = bulkUp.url, maxInFlight = 2u, maxQueued = 1u))) }
                }
                ProxyUnderTest.start("lane-gate-burst", tune).use { p ->
                    runBlocking {
                        // Two bulk requests take both slots upstream, a third waits in line.
                        val burst =
                            (1..3).map { n ->
                                async(Dispatchers.Default) { bulk(p.url).status }.also { if (n <= 2) eventually("bulk slot $n") { held.arrived.value == n } }
                            }
                        eventually("a queued bulk request") { p.state.queued(Lane.Bulk) == 1 }
                        // The line is full: the next bulk request is told to come back and never goes upstream.
                        val away = bulk(p.url)
                        assertEquals(429, away.status)
                        assertEquals("1", away.headers.firstOrNull { it.first.equals("retry-after", true) }?.second)
                        val err = (SerdeJson.parse(away.text()) as JsonObject)["error"] as JsonObject
                        assertEquals(JsonPrimitive("rate_limit_error"), err["type"])
                        // Every bulk slot is still held: an agent turn answers anyway, from its own upstream.
                        assertEquals(200, call("POST", "${p.url}/v1/messages", GATE_BODY, "content-type" to "application/json").status)
                        // A Batch call marked bulk keeps the provider upstream, never the bulk lane's.
                        assertEquals(200, call("POST", "${p.url}/v1/messages/batches", GATE_BODY, ProxyHttp.LANE_HEADER to "bulk").status)
                        assertEquals(2 to 1, held.arrived.value to p.state.queued(Lane.Bulk))
                        held.release(3)
                        assertEquals(listOf(200, 200, 200), burst.awaitAll())
                    }
                    assertEquals(3, held.arrived.value, "the turned-away request never went upstream")
                    assertEquals(0, p.state.queued(Lane.Bulk))
                    assertEquals(2, answered.hits)
                }
            }
        }
    }

    @Test fun proxySwitchedOffCapsNothing() {
        val held = Held()
        Served(held, "held-upstream-off").use { bulkUp ->
            val tune = { c: rtok.config.Config ->
                c.proxy { copy(enabled = false, lanes = lanes.copy(bulk = lanes.bulk.copy(upstream = bulkUp.url, maxInFlight = 1u, maxQueued = 0u))) }
            }
            ProxyUnderTest.start("lane-gate-off", tune).use { p ->
                runBlocking {
                    val burst = (1..3).map { async(Dispatchers.Default) { bulk(p.url).status } }
                    eventually("all three bulk requests upstream at once") { held.arrived.value == 3 }
                    assertEquals(0, p.state.queued(Lane.Bulk))
                    held.release(3)
                    assertEquals(listOf(200, 200, 200), burst.awaitAll())
                }
            }
        }
    }
}
