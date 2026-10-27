package rtok.web.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageTest {
    private fun o(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun usdLikeTheCli() {
        assertEquals("-", usd(null))
        assertEquals("$0.00", usd(0.0))
        assertEquals("$1,234.57", usd(1234.567))
        assertEquals("-$0.05", usd(-0.049))
        assertEquals("$0.00", usd(-0.001))
    }

    @Test fun linesFromAgentsOrModels() {
        val agents = o("""{"agents":[{"host":"claude","name":"Claude","tokens":10,"cost_usd":null,"coverage":0.5}]}""")
        assertEquals(listOf(UsageLine("claude", "Claude", 10.0, null, coverage = 0.5)), usageLines(agents))
        val models = o("""{"agents":[],"models":[{"model":"m1","tokens":3,"cost_usd":1.5}]}""")
        assertEquals(listOf(UsageLine("m1", "m1", 3.0, 1.5)), usageLines(models))
    }

    @Test fun periodKindAndUnpriced() {
        assertEquals("Daily", periodKind(o("""{"periods":[{"period":"2026-10-27"}]}""")))
        assertEquals("Monthly", periodKind(o("""{"periods":[{"period":"2026-10"}]}""")))
        assertEquals(7.0, unpricedTokens(o("""{"unpriced":[{"tokens":3},{"tokens":4}]}""")))
        assertEquals("50%", wholePct(0.5))
        assertEquals("-", wholePct(null))
    }
}
