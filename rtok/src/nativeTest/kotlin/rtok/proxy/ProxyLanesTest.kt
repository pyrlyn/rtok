@file:Suppress("MagicNumber")

// Port of tests/proxy_lanes.rs (T385.1): every lane is tagged in the ledger, its marker never reaches upstream,
// a `/lane/<name>` prefix is stripped, and with lanes off nothing is tagged and the marker goes out as sent.

package rtok.proxy

import rtok.store.countKind
import kotlin.test.Test
import kotlin.test.assertEquals

private const val BODY = """{"model":"m","messages":[]}"""

class ProxyLanesTest {
    @Test fun eachLanePathIsTaggedAndTheMarkerNeverReachesUpstream() {
        val clean = Mock({ it.header(ProxyHttp.LANE_HEADER) == null }, chunks = listOf("{}"))
        FakeUpstream(clean).use { up ->
            ProxyUnderTest.start("lanes", { it.proxy { copy(upstream = up.url, openaiUpstream = up.url) } }).use { p ->
                val cases =
                    listOf(
                        Triple("/v1/messages", null, "api_request"),
                        Triple("/v1/messages/batches", null, "api_request:batch"),
                        Triple("/v1/batches", null, "api_request:batch"),
                        Triple("/v1/files", null, "api_request:files"),
                        Triple("/v1/embeddings", null, "api_request:embeddings"),
                        Triple("/v1/models", null, "api_request:meta"),
                        Triple("/v1/messages/count_tokens", null, "api_request:meta"),
                        Triple("/v1/messages", "bulk", "api_request:bulk"),
                        Triple("/lane/internal/v1/messages", null, "api_request:internal"),
                        // A path that names its lane beats the marker.
                        Triple("/v1/messages/batches", "bulk", "api_request:batch"),
                    )
                for ((path, header, _) in cases) {
                    val h = listOfNotNull(header?.let { ProxyHttp.LANE_HEADER to it }).toTypedArray()
                    assertEquals(200, call("POST", "${p.url}$path", BODY, *h).status, "$path $header")
                }
                for (kind in cases.map { it.third }.distinct()) {
                    val n = cases.count { it.third == kind }.toLong()
                    eventually(kind) { p.state.store.countKind(kind) >= n }
                    assertEquals(n, p.state.store.countKind(kind), kind)
                }
            }
        }
    }

    @Test fun lanePrefixIsStrippedBeforeForwarding() {
        val m = Mock({ it.method == "POST" && it.path == "/v1/messages" && it.body == BODY }, chunks = listOf("{}"))
        FakeUpstream(m).use { up ->
            ProxyUnderTest.start("lanes-prefix", { it.proxy { copy(upstream = up.url) } }).use { p ->
                call("POST", "${p.url}/lane/bulk/v1/messages", BODY)
                assertEquals(1, m.hits)
            }
        }
    }

    @Test fun lanesOffTagsNothingAndForwardsTheMarkerAsSent() {
        val m = Mock({ it.path == "/v1/messages" && it.header(ProxyHttp.LANE_HEADER) == "bulk" }, chunks = listOf("{}"))
        FakeUpstream(m).use { up ->
            ProxyUnderTest.start("lanes-off", { it.proxy { copy(upstream = up.url, lanes = lanes.copy(enabled = false)) } }).use { p ->
                call("POST", "${p.url}/v1/messages", BODY, ProxyHttp.LANE_HEADER to "bulk")
                assertEquals(1, m.hits)
                eventually("agent row") { p.state.store.countKind("api_request") >= 1 }
                assertEquals(0, p.state.store.countKind("api_request:bulk"))
            }
        }
    }
}
