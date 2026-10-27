// The pure part of Doctor.tsx: summary counts, chain hops, module cells, hook bars.
package rtok.web.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class DoctorTest {
    private fun o(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun summaryCountsEveryStateInTileOrder() {
        val c = listOf(Check("pass", "a", ""), Check("pass", "b", ""), Check("skip", "c", ""))
        assertEquals(listOf(2, 0, 0, 1), doctorSummary(c).map { it.second })
        assertEquals("not set", doctorSummary(c)[3].first.second)
    }

    @Test fun chainHopsSplitOnTheArrow() {
        assertEquals(listOf("rtok", "api.anthropic.com"), chainHops("rtok → api.anthropic.com"))
        assertEquals(emptyList(), chainHops(""))
        assertEquals(emptyList(), chainHops(null))
    }

    @Test fun moduleCellsFollowTheState() {
        val a = o("""{"host":"claude","modules":[{"name":"hooks","state":"installed","note":"x"},{"name":"mcp","state":"not_installed","note":"run setup"},{"name":"proxy","state":"not_supported","note":""}]}""")
        assertEquals(ModuleCell("on", "ok", null), moduleCell(a, "hooks"))
        assertEquals(ModuleCell("off", "warn", "run setup"), moduleCell(a, "mcp"))
        assertEquals(ModuleCell("n/a", "muted", null), moduleCell(a, "proxy"))
        assertEquals(ModuleCell("-", "muted", null), moduleCell(a, "plugin"))
    }

    @Test fun hookBarsScaleToTheBusiestEvent() {
        val bars = hookBars(o("""{"hooks_by_event":{"PreToolUse":4,"Stop":1}}"""))
        assertEquals(listOf("PreToolUse" to 1.0, "Stop" to 0.25), bars.map { it.first to it.third })
        assertEquals(emptyList(), hookBars(o("{}")))
    }
}
