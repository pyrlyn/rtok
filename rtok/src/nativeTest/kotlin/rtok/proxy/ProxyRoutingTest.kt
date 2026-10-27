// Port of tests/proxy_routing.rs (T385.10): bulk and internal calls follow their lane's model route, the agent lane
// never does, a routed call is marked, logged and priced on the serving model, and nothing routes while off or
// without a named gate.
@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.Config
import rtok.config.RouteRule
import rtok.measure.stats.Report
import rtok.measure.stats.attachCosts
import rtok.store.SerdeJson
import rtok.store.callsAfter
import rtok.store.listMeasurements
import rtok.store.logsAfter
import rtok.store.usageByLaneModelTier
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ROUTE_CHAT = "/v1/chat/completions"
private const val BIG = "gpt-5"
private const val SMALL = "gpt-5-mini"
private const val ROUTE_USAGE = """"usage":{"prompt_tokens":1000000,"completion_tokens":100000,"total_tokens":1100000}"""

private fun modelSink() = Mock({ true }, headers = listOf("content-type" to "application/json"), chunks = listOf("""{"id":"x",$ROUTE_USAGE}"""))

private fun FakeUpstream.models() = seen.map { ((SerdeJson.parse(it.body) as JsonObject)["model"] as JsonPrimitive).content }

private fun rule(
    from: List<String>,
    to: String,
    gate: String,
) = RouteRule(from = from, to = to, gate = gate)

private fun routingOn(
    c: Config,
    base: String,
) = c.proxy { copy(openaiUpstream = base, upstream = base, routing = routing.copy(enabled = true, bulk = rule(listOf(BIG), SMALL, "bench/bulk.md"), internal = rule(listOf("gpt-*"), SMALL, "bench/internal.md"))) }

private fun send(
    url: String,
    lane: String?,
    session: String,
) {
    val h = listOfNotNull("content-type" to "application/json", lane?.let { ProxyHttp.LANE_HEADER to it }).toTypedArray()
    call("POST", "$url$ROUTE_CHAT", """{"model":"$BIG","user":"$session","messages":[]}""", *h)
}

private fun ProxyUnderTest.usageRows(n: Int) = eventually("$n usage rows") { state.store.usageByLaneModelTier().size >= n }

private fun laneDollars(
    tag: String,
    routed: Boolean,
): rtok.measure.stats.CostReport =
    FakeUpstream(modelSink()).use { up ->
        ProxyUnderTest.start(tag, { routingOn(it, up.url).proxy { copy(routing = routing.copy(enabled = routed)) } }).use { p ->
            send(p.url, "bulk", "s-bulk")
            send(p.url, "internal", "s-internal")
            send(p.url, null, "s-agent")
            p.usageRows(3)
            Report().also { it.attachCosts(p.state.store, Config().stats.prices) }.cost!!
        }
    }

class ProxyRoutingTest {
    @Test fun bulkAndInternalFollowTheirRuleAndTheAgentLaneNeverDoes() {
        FakeUpstream(modelSink()).use { up ->
            ProxyUnderTest.start("routing-lanes", { routingOn(it, up.url) }).use { p ->
                send(p.url, "bulk", "s-bulk")
                send(p.url, "internal", "s-internal")
                send(p.url, null, "s-agent")
                send(p.url, "embeddings", "s-other")
                p.usageRows(4)
                assertEquals(listOf(SMALL, SMALL, BIG, BIG), up.models())
                val served =
                    p.state.store
                        .usageByLaneModelTier()
                        .map { (it.kind ?: "") to it.usage.model }
                        .sortedBy { it.first }
                assertEquals(listOf("api_request" to BIG, "api_request:bulk" to SMALL, "api_request:embeddings" to BIG, "api_request:internal" to SMALL), served)
            }
        }
    }

    @Test fun aRoutedCallIsMarkedLoggedAndRecordedOnTheServingModel() {
        FakeUpstream(modelSink()).use { up ->
            ProxyUnderTest.start("routing-ledger", { routingOn(it, up.url).proxy { copy(routing = routing.copy(bulk = routing.bulk.copy(provider = "gateway"))) } }).use { p ->
                send(p.url, "bulk", "s-bulk")
                p.usageRows(1)
                assertEquals(
                    listOf("model_route" to true),
                    p.state.store
                        .listMeasurements("proxy")
                        .map { it.kind to (it.beforeBytes == it.afterBytes) },
                )
                val logs =
                    p.state.store
                        .logsAfter(0, 100)
                        .map { it.message }
                assertTrue("model route on the bulk lane: $BIG -> $SMALL" in logs, "$logs")
                assertEquals(
                    "api_request:bulk",
                    p.state.store
                        .callsAfter(0, 10)[0]
                        .kind,
                )
            }
        }
    }

    @Test fun nothingIsRoutedWhileOffOrWithoutTheGate() {
        val cases =
            listOf<Pair<String, (Config) -> Config>>(
                "routing-off" to { c -> c.proxy { copy(routing = routing.copy(enabled = false)) } },
                "routing-no-gate" to { c -> c.proxy { copy(routing = routing.copy(bulk = routing.bulk.copy(gate = ""), internal = routing.internal.copy(gate = "  "))) } },
                "routing-no-from" to { c -> c.proxy { copy(routing = routing.copy(bulk = routing.bulk.copy(from = emptyList()), internal = routing.internal.copy(from = emptyList()))) } },
            )
        for ((tag, tune) in cases) {
            FakeUpstream(modelSink()).use { up ->
                ProxyUnderTest.start(tag, { tune(routingOn(it, up.url)) }).use { p ->
                    send(p.url, "bulk", "s1")
                    send(p.url, "internal", "s2")
                    assertEquals(listOf(BIG, BIG), up.models(), tag)
                }
            }
        }
    }

    @Test fun statsPriceShowsDollarsPerLaneBeforeAndAfter() {
        val before = laneDollars("routing-before", false)
        val after = laneDollars("routing-after", true)
        val cost = { m: String -> Config().stats.prices[m]!!.let { it.input + 0.1 * it.output } }
        val big = cost(BIG)
        val small = cost(SMALL)
        assertTrue(small < big)
        val r = { v: Double -> round(v * 1e6) / 1e6 }
        for (lane in listOf("agent", "bulk", "internal")) assertEquals(r(big), before.lanes[lane]!!.cost, "$lane before")
        assertEquals(r(big), after.lanes["agent"]!!.cost)
        assertEquals(r(small), after.lanes["bulk"]!!.cost)
        assertEquals(r(small), after.lanes["internal"]!!.cost)
        assertTrue(after.totalCost < before.totalCost)
        assertFalse(after.lanes.values.any { it.partial })
    }
}
