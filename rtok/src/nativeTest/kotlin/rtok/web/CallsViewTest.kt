// Port of the tests of src/web/calls_view.rs.
// The figures are the Rust tests' own, kept literal so the two suites read side by side.
@file:Suppress("MagicNumber")

package rtok.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallsViewTest {
    @Test
    fun everyWindowCarriesTheTotalsTheStoreComputes() {
        val s = CallsStore()
        s.fold(batch(listOf(end("old", 10, 5)), 0), T)
        s.fold(batch(listOf(end("new", 40, 5), event("run")), 0), T + 9 * 60_000)
        val v = CallsView.of(s, T + 9 * 60_000 + 1000)
        assertEquals(listOf("1 min", "5 min", "15 min", "since start"), v.windows.map { it.label })
        assertEquals(listOf(40L, 40L, 50L, 50L), v.windows.map { it.before })
        assertEquals(s.all.calls, v.windows[3].calls)
        assertEquals(1, v.running.size)
        assertEquals(2, v.feed.size)
    }

    @Test
    fun toolsAreListedMostCallsFirstAndLatencyIsTheMeasuredPercentile() {
        val s = CallsStore()
        s.fold(batch(listOf(end("a", 2, 1), end("b", 2, 1).copy(tool = "impact"), end("c", 2, 1)), 0), T)
        val w = CallsView.of(s, T).windows[3]
        assertEquals(listOf("callers" to 2L, "impact" to 1L), w.tools.map { it.tool to it.calls })
        assertEquals(Latency(12.0, 12.0), w.latency)
        assertNull(CallsView.of(CallsStore(), T).windows[0].latency)
    }

    @Test
    fun theWireNamesAreTheFieldsThePageReads() {
        val v = Json.encodeToJsonElement(CallsView.serializer(), CallsView.of(CallsStore(), T)).jsonObject
        val w = v["windows"]!!.jsonArray[1].jsonObject
        assertEquals("5 min", w["label"]!!.jsonPrimitive.content)
        assertEquals("0", w["symbols_returned"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, w["latency"])
        val spark = w["spark"]!!.jsonObject
        assertEquals("300000", spark["span_ms"]!!.jsonPrimitive.content)
        assertEquals(30, spark["calls"]!!.jsonArray.size)
        assertEquals("300", v["heat_window_s"]!!.jsonPrimitive.content)
    }
}
