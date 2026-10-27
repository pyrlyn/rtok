@file:Suppress("MagicNumber")

package rtok.otel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

internal operator fun JsonElement?.get(idx: Int): JsonElement? = (this as? JsonArray)?.getOrNull(idx)

internal fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.content

// Port of otlp.rs's tests.
class OtlpTest {
    private fun isHex(s: String) = s.all { it in '0'..'9' || it in 'a'..'f' }

    private fun sample() =
        Span(
            traceId("s1"),
            spanId("call", "7"),
            spanId("session", "s1"),
            "chat claude-x",
            Kind.Client,
            secondsToNs(1_700_000_000),
            secondsToNs(1_700_000_000) + 25_000_000,
            listOf(s("gen_ai.request.model", "claude-x"), i("gen_ai.usage.input_tokens", 100)),
            listOf(Event("rtok.measurement", secondsToNs(1_700_000_000), listOf(i("rtok.est_before", 3), b("rtok.ok", true)))),
            Status.Error("boom"),
        )

    @Test fun idsAreHexStableAndDistinct() {
        val t = traceId("s1")
        assertEquals(32, t.length)
        assertTrue(isHex(t))
        assertEquals(t, traceId("s1"))
        assertNotEquals(t, traceId("s2"))
        val sp = spanId("call", "7")
        assertEquals(16, sp.length)
        assertTrue(isHex(sp))
        assertNotEquals(sp, spanId("session", "7"))
    }

    @Test fun tracesFollowTheJsonMapping() {
        val v = traces(Resource(listOf(s("service.name", "rtok"))), listOf(sample(), sample().copy(kind = Kind.Internal, parent = null)))
        val rs = v["resourceSpans"][0]
        val sp = rs["scopeSpans"][0]["spans"]
        assertEquals("service.name", rs["resource"]["attributes"][0]["key"].text())
        assertEquals("rtok", rs["scopeSpans"][0]["scope"]["name"].text())
        assertEquals(32, sp[0]["traceId"].text()!!.length)
        assertEquals(16, sp[0]["spanId"].text()!!.length)
        assertEquals(16, sp[0]["parentSpanId"].text()!!.length)
        assertEquals("3", sp[0]["kind"].text())
        assertEquals("1", sp[1]["kind"].text())
        assertNull(sp[1]["parentSpanId"])
        assertEquals("1700000000000000000", sp[0]["startTimeUnixNano"].text())
        assertEquals("1700000000025000000", sp[0]["endTimeUnixNano"].text())
        assertEquals("claude-x", sp[0]["attributes"][0]["value"]["stringValue"].text())
        assertTrue(sp[0]["attributes"][1]["value"]["intValue"]!!.jsonPrimitive.isString)
        assertEquals("100", sp[0]["attributes"][1]["value"]["intValue"].text())
        assertEquals("true", sp[0]["events"][0]["attributes"][1]["value"]["boolValue"].text())
        assertEquals("2", sp[0]["status"]["code"].text())
        assertEquals("boom", sp[0]["status"]["message"].text())
    }

    @Test fun logsAndMetricsFollowTheJsonMapping() {
        val res = Resource()
        val v = logs(res, listOf(LogRecord(5, 13, "WARN", "hi", listOf(s("rtok.source", "otel")), traceId("s1"), null)))
        val r = v["resourceLogs"][0]["scopeLogs"][0]["logRecords"][0]
        assertEquals("5", r["timeUnixNano"].text())
        assertEquals("13", r["severityNumber"].text())
        assertEquals("hi", r["body"]["stringValue"].text())
        assertEquals(32, r["traceId"].text()!!.length)
        assertNull(r["spanId"])
        val sum = Sum("rtok.tokens", "{token}", "d", true, 1, 2, listOf(listOf(s("gen_ai.token.type", "input")) to 42L))
        val m = metrics(res, listOf(sum, sum.copy(name = "rtok.tokens.saved", monotonic = false)))["resourceMetrics"][0]["scopeMetrics"][0]["metrics"]
        assertEquals("rtok.tokens", m[0]["name"].text())
        assertEquals("2", m[0]["sum"]["aggregationTemporality"].text())
        assertEquals("true", m[0]["sum"]["isMonotonic"].text())
        assertEquals("42", m[0]["sum"]["dataPoints"][0]["asInt"].text())
        assertEquals("1", m[0]["sum"]["dataPoints"][0]["startTimeUnixNano"].text())
        assertEquals("false", m[1]["sum"]["isMonotonic"].text())
    }
}
