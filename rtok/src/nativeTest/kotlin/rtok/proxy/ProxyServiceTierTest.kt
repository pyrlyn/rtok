// Port of tests/proxy_service_tier.rs (T385.12.2): the tier the response names prices the usage, so Flex usage
// is recorded as `<model>@flex` while a fallback to the default tier stays standard.
package rtok.proxy

import rtok.store.usageByLaneTier
import rtok.store.usageByModelTier
import kotlin.test.Test
import kotlin.test.assertEquals

private const val CHAT = "/v1/chat/completions"
private const val TIER_USAGE = """"usage":{"prompt_tokens":100,"completion_tokens":10,"total_tokens":110}"""

class ProxyServiceTierTest {
    @Test fun theResponseTierPricesFlexUsageAndAFallbackStaysStandard() {
        val mocks =
            listOf("gpt-flexed" to "flex", "gpt-fell-back" to "default").map { (model, tier) ->
                Mock(
                    { it.method == "POST" && it.path == CHAT && model in it.body },
                    headers = listOf("content-type" to "application/json"),
                    chunks = listOf("""{"id":"x","service_tier":"$tier",$TIER_USAGE}"""),
                )
            }
        FakeUpstream(*mocks.toTypedArray()).use { up ->
            ProxyUnderTest.start("service-tier", { it.proxy { copy(upstream = up.url, openaiUpstream = up.url) } }).use { p ->
                for (model in listOf("gpt-flexed", "gpt-fell-back")) {
                    val body = """{"model":"$model","user":"s-$model","messages":[]}"""
                    call("POST", "${p.url}$CHAT", body, "content-type" to "application/json", ProxyHttp.LANE_HEADER to "bulk")
                }
                for (m in listOf("gpt-flexed@flex", "gpt-fell-back")) {
                    eventually(m) {
                        p.state.store
                            .usageByModelTier()
                            .any { it.model == m }
                    }
                }
                val tiers =
                    p.state.store
                        .usageByLaneTier()
                        .map { it.kind to it.tier }
                assertEquals(listOf("api_request:bulk" to "default", "api_request:bulk" to "flex"), tiers)
            }
        }
    }
}
