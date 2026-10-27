@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.*
import rtok.config.FlexPolicy
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private fun flex(
    force: Boolean,
    on429: String,
) = FlexPolicy(force = force, on429 = on429)

private fun applied(
    body: String,
    force: Boolean,
) = Flex.apply("openai", body.encodeToByteArray(), flex(force, "none"))?.decodeToString()

private fun tier(s: String) = Json.parseToJsonElement(s).jsonObject["service_tier"]

private fun waits(
    r: Flex.Retry,
    server: Long?,
) = (0u until 3u).map { n -> r.next(n, server?.seconds)?.let { (w, b) -> w.inWholeSeconds to (b != null) } }

private fun retry(on429: String) = Flex.Retry.of("""{"model":"m"}""".encodeToByteArray(), flex(false, on429).copy(backoffMs = 10_000u))!!

/** Port of src/proxy/flex.rs tests (the send loop's tests arrive with the server). */
class FlexTest {
    @Test fun anOmittedTierIsSplicedInAndEveryOtherByteStays() {
        val body = "{ \"model\" : \"m\",\n  \"messages\": [] }"
        assertEquals("{\"service_tier\":\"flex\",${body.substring(1)}", applied(body, false))
        assertEquals("""{"service_tier":"flex"}""", applied("{}", false))
    }

    @Test fun aClientTierIsRespectedUnlessForced() {
        for (t in listOf("auto", "default", "priority", "flex")) assertNull(applied("""{"model":"m","service_tier":"$t"}""", false), t)
        val forced = Json.parseToJsonElement(applied("""{"model":"m","service_tier":"priority"}""", true)!!).jsonObject
        assertEquals(JsonPrimitive("flex"), forced["service_tier"])
        assertEquals(JsonPrimitive("m"), forced["model"])
        assertNull(applied("""{"service_tier":"flex"}""", true))
    }

    @Test fun aNullTierCountsAsOmitted() = assertEquals(JsonPrimitive("flex"), tier(applied("""{"model":"m","service_tier":null}""", false)!!))

    @Test fun otherWiresAndBodiesAreLeftAlone() {
        for (p in listOf("anthropic", "gemini")) assertNull(Flex.apply(p, """{"model":"m"}""".encodeToByteArray(), flex(true, "none")))
        for (bad in listOf("", "not json", "[1]", "\"x\"")) assertNull(applied(bad, true), bad)
    }

    @Test fun retryAfterStretchesTheWaitButNeverShrinksIt() {
        val b = retry("backoff")
        assertEquals(10L to false, waits(b, 5)[0])
        assertEquals(25L to false, waits(b, 25)[0])
        assertEquals(30L to false, waits(b, 30)[0])
        assertEquals(20L to false, waits(b, null)[1])
        assertEquals(0L to true, waits(retry("default"), 7)[0])
    }

    @Test fun aRetryAfterOverTheCapSkipsTheWait() {
        assertEquals(listOf(null, null, null), waits(retry("backoff"), 31))
        assertEquals(listOf(0L to true, null, null), waits(retry("default"), 3600))
    }

    @Test fun onlyDeltaSecondsAreReadFromRetryAfter() {
        assertEquals(12.seconds, Flex.retryAfter("12"))
        assertEquals(3.seconds, Flex.retryAfter(" 3 "))
        assertEquals(Duration.ZERO, Flex.retryAfter("0"))
        for (v in listOf("Wed, 21 Oct 2026 07:28:00 GMT", "soon", "-5", "+5", "1.5", "", "99999999999999999999999")) {
            assertNull(Flex.retryAfter(v), v)
        }
        assertNull(Flex.retryAfter(null))
    }

    @Test fun fallbackRestoresTheClientTierAndUsesAutoOnlyWithoutOne() {
        val policy = flex(true, "default")

        fun fallback(body: String): JsonElement? {
            val sent = Flex.apply("openai", body.encodeToByteArray(), policy)!!
            assertEquals(JsonPrimitive("flex"), tier(sent.decodeToString()))
            val (_, next) = Flex.Retry.of(body.encodeToByteArray(), policy)!!.next(0u, null)!!
            return tier(next!!.decodeToString())
        }
        assertEquals(JsonPrimitive("priority"), fallback("""{"model":"m","service_tier":"priority"}"""))
        assertEquals(JsonPrimitive("auto"), fallback("""{"model":"m"}"""))
        assertEquals(JsonPrimitive("auto"), fallback("""{"model":"m","service_tier":null}"""))
    }

    @Test fun aFlexLaneReadsForAtLeastTheGuidesFifteenMinutes() {
        assertEquals(900uL, Flex.laneTimeoutS(true, 0u, 600u))
        assertEquals(1800uL, Flex.laneTimeoutS(true, 0u, 1800u))
        assertEquals(120uL, Flex.laneTimeoutS(true, 120u, 600u))
        assertEquals(600uL, Flex.laneTimeoutS(false, 0u, 600u))
        assertEquals(45uL, Flex.laneTimeoutS(false, 45u, 600u))
    }

    @Test fun retryScheduleFollowsThePolicy() {
        val base = """{"model":"m"}""".encodeToByteArray()
        assertNull(Flex.Retry.of(base, flex(false, "none")))
        assertNull(Flex.Retry.of(base, flex(false, "bogus")))
        val r = Flex.Retry.of(base, flex(false, "backoff").copy(retries = 99u, backoffMs = 10_000u))!!
        val w = (0u until 7u).map { n -> r.next(n, null)?.let { (d, b) -> d.inWholeSeconds to b } }
        assertEquals(listOf(10L, 20L, 30L, 30L, 30L, null, null), w.map { it?.first })
        assertTrue(w.filterNotNull().all { it.second == null })
        val d = Flex.Retry.of(base, flex(false, "default"))!!
        val (wait, body) = d.next(0u, null)!!
        assertEquals(Duration.ZERO, wait)
        assertEquals("""{"service_tier":"auto","model":"m"}""", body!!.decodeToString())
        assertNull(d.next(1u, null))
    }
}
