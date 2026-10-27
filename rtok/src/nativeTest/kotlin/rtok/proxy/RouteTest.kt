package rtok.proxy

import kotlinx.serialization.json.*
import rtok.config.RouteRule
import kotlin.test.*

/** Port of src/proxy/route.rs tests. */
class RouteTest {
    private fun rule(
        from: List<String>,
        to: String,
        gate: String,
    ) = RouteRule(from = from, to = to, gate = gate)

    private fun body(model: String) = """{"model":"$model","messages":[]}""".encodeToByteArray()

    @Test fun aMatchingModelIsSwappedAndNothingElse() {
        val r = Route.apply(rule(listOf("big"), "small", "bench-1"), body("big"))!!
        assertEquals("big" to "small", r.from to r.to)
        assertEquals(Json.parseToJsonElement("""{"model":"small","messages":[]}"""), Json.parseToJsonElement(r.body.decodeToString()))
        assertNull(r.provider)
    }

    @Test fun aStarMatchesAPrefixAndNoStarMatchesExactly() {
        val r = rule(listOf("claude-opus-*", "exact"), "cheap", "g")
        assertNotNull(Route.apply(r, body("claude-opus-4-1-20250805")))
        assertNotNull(Route.apply(r, body("exact")))
        assertNull(Route.apply(r, body("exact-2")))
        assertNull(Route.apply(r, body("claude-sonnet-4")))
    }

    @Test fun aRuleMissingAPartNeverFires() {
        val b = body("big")
        assertNull(Route.apply(rule(emptyList(), "small", "g"), b))
        assertNull(Route.apply(rule(listOf("big"), "", "g"), b))
        // No named bench, no routing: the quality gate is not rtok's to waive.
        assertNull(Route.apply(rule(listOf("big"), "small", " "), b))
    }

    @Test fun bodiesWithoutAModelOrJsonPassAndSoDoesTheTargetItself() {
        val r = rule(listOf("*"), "small", "g")
        assertNull(Route.apply(r, """{"messages":[]}""".encodeToByteArray()))
        assertNull(Route.apply(r, "not json".encodeToByteArray()))
        assertNull(Route.apply(r, """{"model":7}""".encodeToByteArray()))
        assertNull(Route.apply(r, body("small")))
    }

    @Test fun theProviderIsReportedOnlyWhenNamed() {
        val r = rule(listOf("big"), "small", "g").copy(provider = " openrouter ")
        assertEquals("openrouter", Route.apply(r, body("big"))!!.provider)
    }
}
