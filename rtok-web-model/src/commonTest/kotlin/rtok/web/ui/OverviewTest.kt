package rtok.web.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class OverviewTest {
    private fun snap(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun formatsMatchFormatTs() {
        assertEquals("-", compact(null))
        assertEquals("950", compact(950.0))
        assertEquals("1.2k", compact(1234.0))
        assertEquals("12k", compact(12_345.0))
        assertEquals("3.4M", compact(3_400_000.0))
        assertEquals("12B", compact(12e9))
        assertEquals("1,234,567", fmt(1_234_567.0))
        assertEquals("1023 B", bytes(1023.0))
        assertEquals("1.5 MB", bytes(1.5 * 1024 * 1024))
        assertEquals("12.5%", pct(0.125))
        assertEquals("-", pct(Double.NaN))
    }

    @Test fun bucketsSpreadCallsBySurface() {
        val calls = (0..2).map { snap("""{"ts":${1000 + it * 100},"surface":"${SURFACES[it]}","ok":${it != 1}}""") }
        val (b, step) = callBuckets(calls, 0.0, n = 3)
        assertEquals(67.0, step)
        assertEquals(listOf(1, 0, 0), b.map { it.bySurface.getValue("hook") })
        assertEquals(1, b.sumOf { it.err })
        assertEquals(3, b.sumOf { it.total })
    }

    @Test fun overviewDerivesSavingsAndLatency() {
        val o =
            OverviewModel(
                snap(
                    """{"usage":{"input":10,"cache_create":0,"cache_read":30,"output":5},
                    "plugins":[{"id":"a","enabled":true,"stats":{"est_before":100,"est_after":40}},
                               {"id":"b","enabled":false,"stats":null}],
                    "calls":[{"ts":1,"ms":5,"ok":true},{"ts":2,"ms":50,"ok":false}],
                    "sessions":[{"id":"s","host":"h","started_at":0,"ended_at":null,"last_activity":2}],
                    "doctor":null,"doctor_pending":true}""",
                ),
                10.0,
            )
        assertEquals(60.0, o.saved)
        assertEquals(0.6, o.deltaPct)
        assertEquals(0.75, o.cacheHit)
        assertEquals(1, o.failed)
        assertEquals(50.0, o.p95)
        assertEquals(1, o.live)
        assertEquals(1, o.enabled)
        assertEquals(listOf(Check("skip", "doctor probe", "running doctor…")), o.checks)
    }

    @Test fun doctorChecksReadTheReport() {
        val c =
            doctorChecks(
                snap("""{"hooks_total":3,"hooks_by_event":{"a":1,"b":2},"mcp":[],"proxy":"x","proxy_openai":"","overlaps":["o"]}"""),
                false,
            )
        assertEquals("pass" to "3 hooks across 2 events", c[0].st to c[0].detail)
        assertEquals("warn", c[1].st)
        assertEquals("skip" to "not configured", c[3].st to c[3].detail)
        assertEquals(Check("warn", "host overlap", "o"), c.last())
    }
}
